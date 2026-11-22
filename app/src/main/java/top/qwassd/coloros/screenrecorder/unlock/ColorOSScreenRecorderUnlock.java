package top.qwassd.coloros.screenrecorder.unlock;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Narrow-scope LSPosed module keeping com.oplus.screenrecorder active across lock screen / AOD.
 *
 * Verified on:
 * - PKG110_16.0.9.400(CN01), Android 16/API 36, com.oplus.screenrecorder 16.7.2 (160007002)
 * - PKG110_17.0.0.105(CN01), Android 17/API 37, com.oplus.screenrecorder 17.4.18 (170004018)
 *
 * Every hook fails open: a missing class/member preserves stock behaviour instead of risking
 * system_server stability. No generic MediaProjection.stop(), VirtualDisplay.release(),
 * MediaRecorder.stop(), MediaCodec.stop(), or FLAG_SECURE hook is installed.
 */
public final class ColorOSScreenRecorderUnlock extends XposedModule {
    private static final String TAG = "ColorOSScreenRecorderUnlock";
    private static final String TARGET = "com.oplus.screenrecorder";
    private static final String RECORDER_VIRTUAL_DISPLAY = "OPLUSScreenRecording";
    private static final String ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";
    private static final String ACTION_SHUTDOWN = "android.intent.action.ACTION_SHUTDOWN";
    private static final String RECORDER_SERVICE_CLASS = "com.oplus.screenrecorder.RecorderService";
    private static final String SCREEN_OFF_MONITOR_CLASS =
            "com.oplus.screenrecorder.floatwindow.monitor.l";
    private static final String[] PAUSE_HANDLER_CANDIDATES = {
            "k6.g", // ColorOS 17 (com.oplus.screenrecorder 17.4.18)
            "z4.h", // ColorOS 16 (com.oplus.screenrecorder 16.7.2)
    };

    private final AtomicBoolean recorderHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean systemHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean projectionActive = new AtomicBoolean(false);
    private final AtomicBoolean recorderStopLogged = new AtomicBoolean(false);
    private final AtomicBoolean recorderPauseLogged = new AtomicBoolean(false);
    private final AtomicBoolean keyguardLogged = new AtomicBoolean(false);
    private final AtomicBoolean virtualDisplayLogged = new AtomicBoolean(false);
    private final AtomicBoolean contentRecorderLogged = new AtomicBoolean(false);
    private final Set<Class<?>> hookedReceiverClasses = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> hookedScreenListenerClasses = ConcurrentHashMap.newKeySet();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        report("module loaded; framework=" + getFrameworkName() + " api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage() || !TARGET.equals(param.getPackageName())) {
            return;
        }
        installRecorderHooks(param.getDefaultClassLoader());
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        installSystemServerHooks(param.getClassLoader());
    }

    private void installRecorderHooks(ClassLoader loader) {
        if (!recorderHooksInstalled.compareAndSet(false, true)) {
            return;
        }
        int count = 0;

        // 1) "screen-off pause" disabled:
        //    monitor.l.c(SCREEN_OFF) -> SCREEN_OFF_OR_SHUT_DOWN -> stop_record (action 128).
        int stopHooks = installVerifiedScreenOffStopHook(loader);
        if (stopHooks == 0) {
            stopHooks = installScreenOffReceiverFallbackHook();
        }
        count += stopHooks;

        // 2) "screen-off pause" enabled:
        //    monitor.h.onScreenOff -> POWER_OFF -> RecordPresenter.F(false, isRecording) -> pause().
        int pauseHooks = installVerifiedScreenOffPauseHook(loader);
        if (pauseHooks == 0) {
            pauseHooks = installPowerOffListenerFallbackHook(loader);
        }
        count += pauseHooks;

        report("[Recorder] installed hooks=" + count + " target=" + TARGET);
    }

    private int installVerifiedScreenOffStopHook(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(SCREEN_OFF_MONITOR_CLASS, false, loader);
            if (!hasFieldOfType(type, Context.class)
                    || !hasFieldOfType(type, BroadcastReceiver.class)) {
                report("[Recorder] skip " + SCREEN_OFF_MONITOR_CLASS + ": fingerprint mismatch");
                return 0;
            }
            return installMethodWithSignature(type, "c", new Class<?>[]{String.class},
                    void.class, new ScreenOffStopHooker());
        } catch (Throwable error) {
            report("skip " + SCREEN_OFF_MONITOR_CLASS + ".c: "
                    + error.getClass().getSimpleName() + " " + safeMessage(error));
            return 0;
        }
    }

    private int installScreenOffReceiverFallbackHook() {
        int count = 0;
        for (Method method : ContextWrapper.class.getDeclaredMethods()) {
            if (!"registerReceiver".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 2
                    || params[0] != BroadcastReceiver.class
                    || params[1] != IntentFilter.class) {
                continue;
            }
            try {
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(new RegisterScreenOffReceiverFallbackHooker());
                count++;
            } catch (Throwable error) {
                report("[Recorder] skip registerReceiver fallback: "
                        + error.getClass().getSimpleName() + " " + safeMessage(error));
            }
        }
        report("[Recorder] installed screen-off receiver fallback hooks=" + count);
        return count;
    }

    private int installVerifiedScreenOffPauseHook(ClassLoader loader) {
        for (String candidate : PAUSE_HANDLER_CANDIDATES) {
            try {
                Class<?> type = Class.forName(candidate, false, loader);
                if (!isRecordPresenterClass(type)) {
                    report("[Recorder] skip " + candidate + ": presenter fingerprint mismatch");
                    continue;
                }
                int installed = installMethodWithSignature(type, "F",
                        new Class<?>[]{boolean.class, boolean.class}, void.class,
                        new ScreenOffPauseHooker());
                if (installed > 0) {
                    return installed;
                }
            } catch (ClassNotFoundException ignored) {
                report("skip " + candidate + ".F: ClassNotFoundException");
            } catch (Throwable error) {
                report("skip " + candidate + ".F: "
                        + error.getClass().getSimpleName() + " " + safeMessage(error));
            }
        }
        return 0;
    }

    private int installPowerOffListenerFallbackHook(ClassLoader loader) {
        try {
            Class<?> powerManagerClass = Class.forName("android.os.OplusPowerManager", false, loader);
            int count = 0;
            for (Method method : powerManagerClass.getDeclaredMethods()) {
                if (!"registerScreenStatusListener".equals(method.getName())
                        || method.getParameterTypes().length != 1) {
                    continue;
                }
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(new RegisterScreenStatusFallbackHooker());
                count++;
            }
            report("[Recorder] installed power-off listener fallback hooks=" + count);
            return count;
        } catch (Throwable error) {
            report("[Recorder] skip power-off listener fallback: "
                    + error.getClass().getSimpleName() + " " + safeMessage(error));
            return 0;
        }
    }

    private void installSystemServerHooks(ClassLoader loader) {
        if (!systemHooksInstalled.compareAndSet(false, true)) {
            return;
        }
        int count = 0;

        // Android 16/17 keyguard policy on this ROM. These two methods are the actual policy gates;
        // there is no canCaptureKeyguard() method in services.jar.
        count += installMethod(loader,
                "com.android.server.media.projection.MediaProjectionStopController",
                "isExemptFromStopping", 2, new KeyguardExemptionHooker(true));
        count += installMethod(loader,
                "com.android.server.media.projection.MediaProjectionStopController",
                "isStartForbidden", 1, new KeyguardExemptionHooker(false));

        // Track only the built-in recorder grant. This is diagnostic state and is not used to
        // broaden ownership checks in ContentRecorder or VirtualDisplayAdapter.
        count += installMethod(loader,
                "com.android.server.media.projection.MediaProjectionManagerService",
                "startProjectionLocked", 1, new ProjectionLifecycleHooker(true));
        count += installMethod(loader,
                "com.android.server.media.projection.MediaProjectionManagerService",
                "stopProjectionLocked", 2, new ProjectionLifecycleHooker(false));

        // Keep only the recorder-owned virtual display powered while the physical panel dozes.
        count += installMethod(loader,
                "com.android.server.display.VirtualDisplayAdapter$VirtualDisplayDevice",
                "requestDisplayStateLocked", 4, new VirtualDisplayPowerHooker());

        // Android 16/17 ContentRecorder.updateRecording() removes its mirror when the consuming
        // display is STATE_OFF. Bypass only that exact branch and only when DisplayInfo says the
        // consuming virtual display owner is com.oplus.screenrecorder.
        count += installMethod(loader, "com.android.server.wm.ContentRecorder",
                "updateRecording", 0, new ContentRecorderHooker());

        report("[System] installed hooks=" + count);
    }

    private int installMethod(ClassLoader loader, String className, String methodName,
                              int parameterCount, XposedInterface.Hooker hooker) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            int count = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (!methodName.equals(method.getName())
                        || method.getParameterTypes().length != parameterCount) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // LSPosed can still hook many hidden methods without changing accessibility.
                }
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(hooker);
                count++;
            }
            report("hook " + className + "." + methodName + "/" + parameterCount
                    + " count=" + count);
            return count;
        } catch (Throwable error) {
            report("skip " + className + "." + methodName + ": "
                    + error.getClass().getSimpleName() + " " + safeMessage(error));
            return 0;
        }
    }

    private int installMethodWithSignature(Class<?> type, String methodName,
                                           Class<?>[] parameterTypes, Class<?> returnType,
                                           XposedInterface.Hooker hooker) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())
                    || !Arrays.equals(method.getParameterTypes(), parameterTypes)
                    || (returnType != null && method.getReturnType() != returnType)) {
                continue;
            }
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
            }
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            count++;
        }
        report("hook " + type.getName() + "." + methodName + "/" + parameterTypes.length
                + " count=" + count);
        return count;
    }

    private static boolean isRecordPresenterClass(Class<?> type) {
        return hasFieldOfType(type, JobScheduler.class)
                && hasFieldOfType(type, JobInfo.class)
                && hasFieldOfTypeName(type, RECORDER_SERVICE_CLASS)
                && hasDeclaredMethod(type, "pause", 0)
                && hasDeclaredMethod(type, "stop", 0);
    }

    private static boolean hasFieldOfType(Class<?> type, Class<?> fieldType) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() == fieldType) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFieldOfTypeName(Class<?> type, String typeName) {
        for (Field field : type.getDeclaredFields()) {
            if (typeName.equals(field.getType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDeclaredMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())
                    && method.getParameterTypes().length == parameterCount) {
                return true;
            }
        }
        return false;
    }

    private final class ScreenOffStopHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object action = chain.getArg(0);
                if (ACTION_SCREEN_OFF.equals(action)) {
                    if (recorderStopLogged.compareAndSet(false, true)) {
                        report("[Recorder] intercepted screen-off stop event");
                    }
                    return null;
                }
            } catch (Throwable error) {
                report("[Recorder] stop hook fail-open: " + error.getClass().getSimpleName());
            }
            return chain.proceed();
        }
    }

    private final class RegisterScreenOffReceiverFallbackHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object receiver = chain.getArg(0);
                Object filter = chain.getArg(1);
                if (receiver instanceof BroadcastReceiver && filter instanceof IntentFilter) {
                    IntentFilter intentFilter = (IntentFilter) filter;
                    if (intentFilter.hasAction(ACTION_SCREEN_OFF)
                            && intentFilter.hasAction(ACTION_SHUTDOWN)) {
                        Class<?> receiverClass = receiver.getClass();
                        if (hookedReceiverClasses.add(receiverClass)) {
                            installMethodWithSignature(receiverClass, "onReceive",
                                    new Class<?>[]{Context.class, Intent.class}, void.class,
                                    new ScreenOffReceiverFallbackHooker());
                        }
                    }
                }
            } catch (Throwable error) {
                report("[Recorder] registerReceiver fallback fail-open: "
                        + error.getClass().getSimpleName());
            }
            return chain.proceed();
        }
    }

    private final class ScreenOffReceiverFallbackHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object intentObj = chain.getArg(1);
                if (intentObj instanceof Intent
                        && ACTION_SCREEN_OFF.equals(((Intent) intentObj).getAction())) {
                    if (recorderStopLogged.compareAndSet(false, true)) {
                        report("[Recorder] intercepted screen-off stop broadcast (fallback)");
                    }
                    return null;
                }
            } catch (Throwable error) {
                report("[Recorder] stop broadcast fallback fail-open: "
                        + error.getClass().getSimpleName());
            }
            return chain.proceed();
        }
    }

    private final class ScreenOffPauseHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object screenOn = chain.getArg(0);
                if (Boolean.FALSE.equals(screenOn)) {
                    if (recorderPauseLogged.compareAndSet(false, true)) {
                        report("[Recorder] intercepted screen-off pause");
                    }
                    return null;
                }
            } catch (Throwable error) {
                report("[Recorder] pause hook fail-open: " + error.getClass().getSimpleName());
            }
            return chain.proceed();
        }
    }

    private final class RegisterScreenStatusFallbackHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object listener = chain.getArg(0);
                if (listener != null) {
                    Class<?> listenerClass = listener.getClass();
                    if (listenerClass.getName().startsWith(TARGET + ".")
                            && hookedScreenListenerClasses.add(listenerClass)) {
                        installMethodWithSignature(listenerClass, "onScreenOff",
                                new Class<?>[0], void.class,
                                new ScreenOffListenerFallbackHooker());
                    }
                }
            } catch (Throwable error) {
                report("[Recorder] registerScreenStatus fallback fail-open: "
                        + error.getClass().getSimpleName());
            }
            return chain.proceed();
        }
    }

    private final class ScreenOffListenerFallbackHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (recorderPauseLogged.compareAndSet(false, true)) {
                report("[Recorder] intercepted screen-off listener (fallback)");
            }
            return null;
        }
    }

    private final class KeyguardExemptionHooker implements XposedInterface.Hooker {
        private final boolean exemptResult;

        KeyguardExemptionHooker(boolean exemptResult) {
            this.exemptResult = exemptResult;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object projection = chain.getArg(0);
                if (isTargetProjection(projection)) {
                    if (keyguardLogged.compareAndSet(false, true)) {
                        report("[MP] owner=" + TARGET + " allow capture keyguard");
                    }
                    // isExemptFromStopping -> true; isStartForbidden -> false.
                    return exemptResult;
                }
            } catch (Throwable error) {
                report("[MP] keyguard hook fail-open: " + error.getClass().getSimpleName());
            }
            return chain.proceed();
        }
    }

    private final class ProjectionLifecycleHooker implements XposedInterface.Hooker {
        private final boolean starting;

        ProjectionLifecycleHooker(boolean starting) {
            this.starting = starting;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object projection = null;
            boolean target = false;
            try {
                projection = chain.getArg(0);
                target = isTargetProjection(projection);
            } catch (Throwable error) {
                report("[MP] lifecycle inspect fail-open: " + error.getClass().getSimpleName());
            }

            Object result = chain.proceed();
            if (target) {
                projectionActive.set(starting);
                if (starting) {
                    recorderStopLogged.set(false);
                    recorderPauseLogged.set(false);
                    keyguardLogged.set(false);
                    virtualDisplayLogged.set(false);
                    contentRecorderLogged.set(false);
                    report("[MP] MediaProjection owner=" + TARGET + " started");
                } else {
                    report("[MP] MediaProjection owner=" + TARGET + " stopped normally");
                }
            }
            return result;
        }
    }

    private final class VirtualDisplayPowerHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object state = chain.getArg(0);
                Object receiver = chain.getThisObject();
                if (Integer.valueOf(1).equals(state) && isRecorderVirtualDisplay(receiver)) {
                    Object[] args = chain.getArgs().toArray();
                    args[0] = 2; // android.view.Display.STATE_ON
                    if (virtualDisplayLogged.compareAndSet(false, true)) {
                        report("[Recorder] keep VirtualDisplay alive during physical display off");
                    }
                    return chain.proceed(args);
                }
            } catch (Throwable error) {
                report("[VD] power hook fail-open: " + error.getClass().getSimpleName());
            }
            return chain.proceed();
        }
    }

    private final class ContentRecorderHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                if (shouldBypassDisplayOffPause(chain.getThisObject())) {
                    if (contentRecorderLogged.compareAndSet(false, true)) {
                        report("[WM] bypass ContentRecorder pause caused by DISPLAY_STATE_OFF");
                    }
                    return null;
                }
            } catch (Throwable error) {
                report("[WM] ContentRecorder hook fail-open: "
                        + error.getClass().getSimpleName() + " " + safeMessage(error));
            }
            return chain.proceed();
        }
    }

    private boolean shouldBypassDisplayOffPause(Object contentRecorder) throws Exception {
        if (contentRecorder == null
                || readField(contentRecorder, "mContentRecordingSession") == null
                || readField(contentRecorder, "mRecordedSurface") == null) {
            return false;
        }

        Object displayContent = readField(contentRecorder, "mDisplayContent");
        if (displayContent == null) {
            return false;
        }
        Object displayInfo = invokeNoArgs(displayContent, "getDisplayInfo");
        if (displayInfo == null) {
            return false;
        }

        Object owner = readField(displayInfo, "ownerPackageName");
        Object state = readField(displayInfo, "state");
        Object lastHasContent = invokeNoArgs(displayContent, "getLastHasContent");
        return TARGET.equals(owner)
                && Integer.valueOf(1).equals(state)
                && Boolean.FALSE.equals(lastHasContent);
    }

    private boolean isRecorderVirtualDisplay(Object device) throws Exception {
        if (device == null) {
            return false;
        }
        Object owner = readField(device, "mOwnerPackageName");
        Object name = readField(device, "mName");
        return TARGET.equals(owner) && RECORDER_VIRTUAL_DISPLAY.equals(name);
    }

    private boolean isTargetProjection(Object projection) {
        if (projection == null) {
            return false;
        }
        try {
            return TARGET.equals(readField(projection, "packageName"));
        } catch (Throwable error) {
            return false;
        }
    }

    private static Object readField(Object receiver, String name) throws Exception {
        Field field = findField(receiver.getClass(), name);
        field.setAccessible(true);
        return field.get(receiver);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invokeNoArgs(Object receiver, String name) throws Exception {
        Method method = findMethod(receiver.getClass(), name, 0);
        method.setAccessible(true);
        return method.invoke(receiver);
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount)
            throws NoSuchMethodException {
        Class<?> cursor = type;
        while (cursor != null) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (name.equals(method.getName())
                        && method.getParameterTypes().length == parameterCount) {
                    return method;
                }
            }
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchMethodException(name + "/" + parameterCount);
    }

    private void report(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
        try {
            log(Log.INFO, TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? "" : message;
    }
}
