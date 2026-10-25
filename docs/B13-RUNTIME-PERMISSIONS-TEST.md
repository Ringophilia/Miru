# B13 Android 运行时权限专项

`RuntimeSitePermissionIntegrationTest` 是独立的系统权限专项，必须在全新 API 35
AVD 上按整个类运行。它通过 `Instrumentation.uiAutomation` 查找并点击 Android
PermissionController 的无障碍节点，不依赖 UiAutomator / Espresso，也不预授予权限。
本文件记录运行方法与有限的设备结果；新增源码本身不能作为通过证据。

## 范围与前置条件

测试使用设备内 loopback HTTP 页面、真实触摸和 Gecko 回调，按方法名顺序验证：

1. 相机、麦克风和位置权限初始均未授予；首次相机请求在真实系统对话框选择拒绝。
   网页收到拒绝，而该来源的相机网站选择仍为 `ASK`，不因 Android 拒绝写入网站 `BLOCK`。
2. 后续组合相机 / 麦克风请求在真实系统对话框选择允许，再在 Miru 网站权限面板选择
   `BLOCK`；网页仍收到拒绝，证明 Android 授权不等于网站同意。
3. 网站位置选择为 `ALLOW` 后，在真实系统位置对话框选择近似位置并允许。断言
   `ACCESS_COARSE_LOCATION` 已授予、`ACCESS_FINE_LOCATION` 未授予；真实 Gecko
   回调只能是取得位置、provider unavailable 或超时，不得是 permission denied，也不得出现
   要求前往系统设置的拒绝提示。

测试要求实际点击预期系统对话框；未出现、无法点击或初始权限状态不符都会失败，不能以
`Assume` 跳过算作通过。不得使用 `pm grant` / `pm revoke`、`adb install -g`、运行器自动
授权、假媒体设备或浏览器权限回调注入。不要先运行会预授予权限的
`SitePermissionIntegrationTest`，也不要单独选择本类中的后续方法或使用逐方法新进程的
Test Orchestrator。本类需要同一次 instrumentation 进程保留顺序完成标记。每次重跑都需恢复全新
AVD 用户数据，不能复用上次已点击过系统权限对话框的资料。

本类不证明网站允许后的相机 / 麦克风实际采集成功；独立网站策略用例已在旁听监听器修复后
取得真实音视频live tracks，证据见B13-PERMISSIONS-VALIDATION.md，不能由本类结果代替。位置没有模拟器 fix 时允许收到
provider unavailable / timeout，因此通过也不代表 GPS 精度或真机定位验收完成。

## 独立运行

创建专用 Android 15 / API 35 AVD，使用其全新用户数据启动；不要使用日常回归的 AVD。
下面以新实例 `emulator-5586` 为例。安装命令不带 `-g`，运行时只选择整个新类：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5586 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5586 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5586 shell am instrument -w -e class app.miru.browser.RuntimeSitePermissionIntegrationTest app.miru.browser.test/androidx.test.runner.AndroidJUnitRunner
```

也可在仅连接该全新目标设备时，运行完整类的 Gradle 入口：

```powershell
$env:ANDROID_SERIAL = 'emulator-5586'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=app.miru.browser.RuntimeSitePermissionIntegrationTest"
```

保留完整 instrumentation 输出、失败堆栈与 logcat，并记录 AVD / API、构建源码及 APK。
成功需要三项均通过、零跳过；不把缺少系统对话框、错误权限初态或单独方法运行当作有效结果。

## 常规全量回归排除

常规套件必须显式排除此类，并保留已有分阶段重启测试的排除项：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=app.miru.browser.DownloadRestartTest,app.miru.browser.OfflineRestartTest,app.miru.browser.RuntimeSitePermissionIntegrationTest"
```

直接使用 instrumentation 时的等价排除入口：

```powershell
adb -s emulator-5570 shell am instrument -w -e notClass app.miru.browser.DownloadRestartTest,app.miru.browser.OfflineRestartTest,app.miru.browser.RuntimeSitePermissionIntegrationTest app.miru.browser.test/androidx.test.runner.AndroidJUnitRunner
```

排除项不计为通过；本专项结果须单独记录，不能扩大为整个 B13 验收。

## API35 实际结果与失败保留

0.5.4开发源码，`054-runtime-denial-device.log` **3/3通过，21.741秒**。
`054-runtime-denial-input.json`记录5590只读新实例安装前无Miru包、被测APK完整摘要；
类的@BeforeClass另断言四项Android权限均未授予。全过程实际点击系统权限节点，
没有预授予、pm grant/revoke、假媒体或回调注入。

前序失败保留在`054-runtime-permissions-device.log`（测试时钟未推进）、
`054-runtime-clock-device.log`（系统资源命名空间匹配失败）、
`054-runtime-fresh-device.log`（真实系统拒绝后，后续Media请求又弹网站面板，网页一直pending）。
测试等待改为同期限compose.waitUntil，系统包归属与资源命名空间分开校验；
生产代码在Android摄像头/麦克风许可缺失时直接拒绝Gecko后续Media请求，不写网站BLOCK。
定位Content先于App的顺序保持，近似位置断言仍要求coarse=true、fine=false。

NativeGate关联原pending，在启动和结果提示前分别验证；迟到回调只排空原slot，
不把系统设置提示投到另一个页面。Activity重建、永久拒绝、迟到结果与所有真机版本矩阵
尚不能仅据这三项关闭；完整B13与release门仍在验收矩阵中保留。
