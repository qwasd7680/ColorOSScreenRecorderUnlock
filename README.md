# ColorOSScreenRecorderUnlock

一个针对 ColorOS 16 / 17 系统录屏的窄作用域 LSPosed 模块：按电源键进入锁屏/AOD 后，
保持 `com.oplus.screenrecorder` 的录制、VirtualDisplay 和 WindowManager mirror 活跃。

已适配并实机验证：

- OnePlus PKG110
- ColorOS 16：`PKG110_16.0.9.400(CN01)`，Android 16 / API 36，`com.oplus.screenrecorder` 16.7.2 (`160007002`)
- ColorOS 17：`PKG110_17.0.0.105(CN01)`，Android 17 / API 37，`com.oplus.screenrecorder` 17.4.18 (`170004018`)
- LSPosed modern API 102

## 实现范围

- 拦截录屏 App 自己的 `SCREEN_OFF → stop_record` 路径（优先精确匹配带指纹校验的 `monitor.l.c(String)`，混淆改名时自动回退到 `SCREEN_OFF + ACTION_SHUTDOWN` 广播接收器拦截）。
- 拦截录屏 App 自己的“熄屏暂停”路径（优先精确匹配带结构指纹校验的 `RecordPresenter.F(boolean, boolean)`，支持 ColorOS 16 `z4.h` 与 ColorOS 17 `k6.g`，混淆改名时自动回退到 `OplusPowerManager.registerScreenStatusListener` 监听器的 `onScreenOff()`），保留用户主动暂停/继续/停止。
- 仅为 `com.oplus.screenrecorder` 豁免 Android 16 / 17 Keyguard MediaProjection stop/start 限制。
- 仅保持该包创建、名称为 `OPLUSScreenRecording` 的 VirtualDisplay 为 ON。
- 仅对 owner 为该包且暂停原因仅为 `DISPLAY_STATE_OFF` 的 `ContentRecorder` 保留 mirror。
- 所有 Hook 独立保护、找不到类或方法时 fail-open，不阻断系统原逻辑。

模块没有全局 Hook `MediaProjection.stop()`、`VirtualDisplay.release()`、编码器 stop/flush，
也没有关闭全局 `FLAG_SECURE`。普通第三方录屏、无线投屏和其他 App 的安全窗口不在作用范围内。

完整的反编译证据和调用链见 [analysis/REPORT.md](analysis/REPORT.md)。

## 安装

1. 编译或安装 APK。
2. 在 LSPosed 中启用模块。
3. 作用域勾选：
   - 系统框架（modern scope 名称为 `system`）
   - 屏幕录制（`com.oplus.screenrecorder`）
4. 重启设备；system_server Hook 只有重启后才会加载。

调试日志：

```text
ColorOSScreenRecorderUnlock
```

典型日志包括 `[MP]`、`[Recorder]`、`[WM]`，不会按帧输出。

## 构建

### Debug 构建

Windows：

```powershell
.\gradlew.bat assembleDebug
```

Linux/macOS：

```bash
./gradlew assembleDebug
```

输出：`app/build/outputs/apk/debug/ColorOSScreenRecorderUnlock-v<versionName>-debug.apk`

### Release 签名构建

1. 参考 [keystore.properties.example](keystore.properties.example) 创建本地 `keystore.properties`（已被 `.gitignore` 忽略），并指定签名证书 `keystore/release.jks` 及密码/别名；或通过环境变量 `SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 传入。
2. 执行：

```powershell
.\gradlew.bat assembleRelease
```

输出：`app/build/outputs/apk/release/ColorOSScreenRecorderUnlock-v<versionName>-release.apk`

### GitHub Actions 自动打包并发布 Release

仓库已配置 [.github/workflows/release.yml](.github/workflows/release.yml)。在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中添加以下 4 个 Secrets：

- `SIGNING_KEYSTORE_BASE64`：`keystore/release.jks` 的 Base64 编码字符串
- `SIGNING_STORE_PASSWORD`：keystore 密码
- `SIGNING_KEY_ALIAS`：证书别名
- `SIGNING_KEY_PASSWORD`：密钥密码

触发方式：

- 推送 `v*` 标签（例如 `git tag v1.1.0 && git push origin v1.1.0`），自动编译签名 APK、校验签名、生成 `SHA256SUMS.txt` 并创建 GitHub Release；
- 或在 GitHub Actions 页面手动运行 **Release APK** workflow（`workflow_dispatch`）。

构建环境使用 JDK 17、Android Gradle Plugin 8.12.3、compileSdk 36。

## 恢复

在测试 system_server 模块前应确保 LSPosed Safe Mode 可用。若出现启动异常：

1. 使用 LSPosed Safe Mode/禁用 LSPosed 启动；
2. 禁用或卸载 `top.qwassd.coloros.screenrecorder.unlock`；
3. 再正常重启。

当前实现的每个 Hook 都使用 protective exception mode 并 fail-open，但仍建议保留恢复路径。
