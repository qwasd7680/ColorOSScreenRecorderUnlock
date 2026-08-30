# PKG110 ColorOS 16 分析与实测报告

## 目标环境

| 项目 | 值 |
|---|---|
| 设备 | OnePlus PKG110 (`OP5D2BL1`) |
| ROM | `PKG110_16.0.9.400(CN01)` |
| Android | 16 / API 36 |
| 录屏包 | `com.oplus.screenrecorder` |
| 录屏版本 | 16.7.2 / `160007002` |

分析输入的 SHA-256（原文件不进入公开仓库）：

| 文件 | SHA-256 |
|---|---|
| OplusScreenRecorder base.apk | `71D47D91B1D90D75A8416659E6166A7BA897BE31C780A2CD77839787566A06EA` |
| `/system/framework/services.jar` | `B48228E4DDBA5F16F0818FBAA668CE93DCE3B3C3728083616635A45D72D89F1B` |
| `/system/framework/framework.jar` | `C528BE257DE9A7AA664245E0E1EE3D5CD3B29B5F790E6E0F8F9F525643955BAB` |

## ColorOS ScreenRecorder 熄屏调用链

### “熄屏暂停”关闭时：直接停止

```text
android.intent.action.SCREEN_OFF
  → com.oplus.screenrecorder.floatwindow.monitor.l$a.onReceive()
  → monitor.l.c("android.intent.action.SCREEN_OFF")
  → event SCREEN_OFF_OR_SHUT_DOWN
  → b5.d0.s(event), case 5
  → m4941O(128)
  → C0961d action 128 = stop_record
  → stop/save/release recorder resources
```

关键设置读取是 `q4.d0.A()`：

```text
Settings.System["recording_settings_enable_screen_off_pause"] == 1
```

关闭该设置并不表示“持续录制”，而是选择上面的 stop 分支。

### “熄屏暂停”开启时：暂停

```text
OplusPowerManager screen status callback
  → com.oplus.screenrecorder.floatwindow.monitor.h.onScreenOff()
  → event POWER_OFF("android.intent.action.SCREEN_OFF")
  → z4.h.s(event), case POWER_OFF
  → z4.h.F(false, isRecording)
  → z4.h.pause()
  → b5.d0.i(true)
  → action 64 = pause
  → e4.a.pause()
  → e4.h.T()
  → video/audio recorder pause
```

因此模块分别 Hook `monitor.l.c(String)` 和 `z4.h.F(boolean, boolean)`，只拦截
`SCREEN_OFF`/`screenOn == false`。关机事件、用户主动暂停和主动停止继续走原逻辑。

## Android 16 Keyguard MediaProjection 限制

这份 ROM 的 `MediaProjectionManagerService` 中没有 `canCaptureKeyguard()`。实际路径是：

```text
MediaProjectionStopController.onKeyguardLockedStateChanged(true)
  → stop reason consumer (STOP_REASON_KEYGUARD)
  → MediaProjectionManagerService.maybeStopMediaProjection(1)
  → MediaProjectionStopController.isExemptFromStopping(...)
  → projection.stop(3) when not exempt
```

豁免条件包括：

- `android.permission.RECORD_SENSITIVE_CONTENT`
- AppOp 46 / `PROJECT_MEDIA == allow`
- companion streaming role
- bugreport allowlist

设备初始 AppOps：

```text
PROJECT_MEDIA: ignore
```

ADB shell 执行 `cmd appops set ... PROJECT_MEDIA allow` 被系统拒绝：shell UID 2000 没有
`MANAGE_APP_OPS_MODES`。因此模块不依赖手工 AppOps，而是在
`isExemptFromStopping`/`isStartForbidden` 中仅识别 projection 的 `packageName` 后返回豁免结果。

当前 Oplus 实现还把系统录屏 grant 单独保存到 `mOplusProjectionGrant`，而不是普通的
`mProjectionGrant`；这本身使部分 AOSP Keyguard stop 路径不会命中系统录屏。Hook 仍保留，
用于同版本其他分支和后续小版本兼容，但不会给第三方 projection 放行。

## WindowManager ContentRecorder

当前 `services.jar` 的真实逻辑：

```java
void updateRecording() {
    if (isCurrentlyRecording()
            && (mDisplayContent.getLastHasContent()
                || mDisplayContent.getDisplayInfo().state == Display.STATE_OFF)) {
        pauseRecording();
    } else {
        startRecordingIfNeeded();
    }
}
```

`pauseRecording()` 会 remove `mRecordedSurface`、恢复 virtual display window/overlay layer 的
parent，随后把 `mRecordedSurface` 设为 null。

模块只在以下条件同时成立时跳过这一次 `updateRecording()`：

- ContentRecordingSession 和 mirror surface 都存在；
- `DisplayInfo.ownerPackageName == com.oplus.screenrecorder`；
- display state 恰好是 `STATE_OFF`；
- `getLastHasContent() == false`，即没有其他暂停原因。

## VirtualDisplay

录屏器 `i4.u.b(...)` 创建：

```text
name  = OPLUSScreenRecording
flags = 0x10 (AUTO_MIRROR)
owner = com.oplus.screenrecorder
```

`VirtualDisplayAdapter.VirtualDisplayDevice.requestDisplayStateLocked(STATE_OFF, ...)`
会发送 display paused callback，并把 SurfaceControl display power mode 切到 OFF。模块仅在
owner 和 name 同时匹配时把该次请求改为 `STATE_ON`；物理显示、其他虚拟显示、Cast 和无线投屏
不受影响。

## AOD SurfaceFlinger capture path

在设备确认处于：

```text
mWakefulness=Dozing
Display state=DOZE_SUSPEND
```

时执行 `adb exec-out screencap -p`，截图中能看到实际 AOD 的背景、签名、日期和时间。
`dumpsys SurfaceFlinger --list` 同时包含：

- `NotificationShade`
- `OnScreenFingerprintIcon`
- `OnScreenFingerprintPressedIcon`

结论为题目中的情况 A：AOD 在标准 SurfaceFlinger capture path 内，不需要
SurfaceFlinger/HWC/Display HAL native Hook 才能获得基本 AOD 画面。原始截图包含个人画面，
故意不进入公开仓库。

## 编译、安装与测试

- `gradlew assembleDebug`：成功。
- APK v2 签名校验：成功。
- `adb install -r`：成功。
- LSPosed 模块与两个静态作用域：已在管理器界面确认启用。
- 设备端实际录屏验证：测试者确认熄屏/AOD/亮屏过程录制正常，模块未破坏正常录屏操作。

公开仓库不包含测试视频、设备截图、logcat、厂商 APK 或 framework jar。

## 重要 Hook 一览

| 进程 | 类/方法 | 行为 |
|---|---|---|
| screenrecorder | `monitor.l.c(String)` | 忽略 `SCREEN_OFF` 触发的 stop event |
| screenrecorder | `z4.h.F(boolean, boolean)` | 忽略 screen-off pause，screen-on/其他路径保留 |
| system_server | `MediaProjectionStopController.isExemptFromStopping` | 只对目标 owner 返回 true |
| system_server | `MediaProjectionStopController.isStartForbidden` | 只对目标 owner 返回 false |
| system_server | `MediaProjectionManagerService.start/stopProjectionLocked` | 目标 grant 生命周期日志/状态 |
| system_server | `VirtualDisplayDevice.requestDisplayStateLocked` | 只保持目标名/owner 的 VD 为 ON |
| system_server | `ContentRecorder.updateRecording` | 只绕过目标 owner 的纯 STATE_OFF pause |

