package top.qwassd.coloros.screenrecorder.unlock;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks verified on PKG110_16.0.9.400(CN01), Android 16/API 36 and
 * com.oplus.screenrecorder 16.7.2 (160007002).
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

    private final AtomicBoolean recorderHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean systemHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean projectionActive = new AtomicBoolean(false);
    private final AtomicBoolean recorderStopLogged = new AtomicBoolean(false);
    private final AtomicBoolean recorderPauseLogged = new AtomicBoolean(false);
    private final AtomicBoolean keyguardLogged = new AtomicBoolean(false);
    private final AtomicBoolean virtualDisplayLogged = new AtomicBoolean(false);
    private final AtomicBoolean contentRecorderLogged = new AtomicBoolean(false);

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

        // Verified path when "screen-off pause" is disabled:
        // monitor.l.c(SCREEN_OFF) -> SCREEN_OFF_OR_SHUT_DOWN -> b5.d0.s -> action 128 (stop_record).
        count += installMethod(loader,
                "com.oplus.screenrecorder.floatwindow.monitor.l", "c", 1,
                new ScreenOffStopHooker());

        // Verified path when "screen-off pause" is enabled:
        // monitor.h.onScreenOff -> POWER_OFF -> z4.h.F(false, isRecording) -> pause().
        count += installMethod(loader, "z4.h", "F", 2, new ScreenOffPauseHooker());

        report("[Recorder] installed hooks=" + count + " target=" + TARGET);
    }

    private void installSystemServerHooks(ClassLoader loader) {
        if (!systemHooksInstalled.compareAndSet(false, true)) {
            return;
        }
        int count = 0;

        // Android 16 keyguard policy on this ROM. These two methods are the actual policy gates;
        // there is no canCaptureKeyguard() method in this services.jar.
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

        // Android 16 ContentRecorder.updateRecording() removes its mirror when the consuming
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

