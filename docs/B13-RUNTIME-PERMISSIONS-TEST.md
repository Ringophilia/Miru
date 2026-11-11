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

## 摄像头权限恢复与生命周期专项

`app.miru.browser.capability.CameraPermissionRecoveryIntegrationTest` 在英语界面的 API 35 模拟器上运行。使用摄像头权限从未请求过的临时 Android 用户，或独立的新模拟器资料。测试在网页上发起真实 `getUserMedia` 请求，点击系统两次拒绝，再通过 Miru 的提示进入 Android 设置，在「权限 → 摄像头」中选择仅在使用应用时允许。返回后验证网站仍处于询问状态，并在网站单独允许后检查视频轨道进入 `live` 状态。Android 对重复拒绝的说明见 [Android 运行时权限文档](https://developer.android.com/training/permissions/requesting)。

使用包含该类的固定 Debug 快照，按方法单独调用设备验证脚本。每个方法都从新的 Android 用户开始。临时用户须先切到前台，并以 `-UserId` 指定其 ID。该类由 capability 包的规则排除在常规回归外。权限变更全程走实际界面，测试结束后恢复原用户并移除本轮创建的临时用户。

```powershell
$testClass = 'app.miru.browser.capability.CameraPermissionRecoveryIntegrationTest'
$method = 'permanentAndroidDenialCanBeRecoveredThroughSettingsWithoutGrantingTheWebsite'
./tools/run-device-validation.ps1 -App -BuildName $FeatureBuildName `
    -RunName camera-permission-recovery-check -UserId $TestUserId `
    -Selector "$testClass#$method" -Expected 1
```

2026-11-11，最终固定快照 `camera-permission-recovery-build-03` 通过编译，709 项输入清单无漂移。Lint 为 0 错误、29 警告。主 APK 的 SHA-256 `231b0830973e2f9cc1c1604dbaf82312fd2f66d5b035ee98ae047fbc6fd562b9` 与受保护内容批次一致。测试 APK 的 SHA-256 为 `478de59ea7c5b894501393efbe53eb92fbcdf69d64eb301991a547bcce2a4a21`。引擎 JVM 任务为 `UP-TO-DATE`，沿用同一输入的 131 项通过和 1 项跳过记录。

`camera-permission-recovery-device-03` 在 API 35 x86_64 模拟器的临时用户 12 上通过 1/1，JUnit 39.755 秒，运行器 56.880 秒。首次拒绝后未显示设置提示。第二次拒绝后，Android 权限仍为拒绝，Miru 显示设置入口，网站选择为「询问」。从 Android 设置授权后返回原网页，网站仍须单独询问，允许后真实 `getUserMedia` 返回 `live` 视频轨道。安装包的哈希与大小核对通过，临时 APK 副本已删除。原用户 0 已恢复，本轮临时用户已移除。

前两轮失败保留在 `camera-permission-recovery-device-01` 和 `camera-permission-recovery-device-02`。两轮均到达第二个系统弹窗，测试未匹配该设备上的 `permission_deny_and_dont_ask_again_button` 控件。首轮清理记录读取早于 Android 完成自动移除，后续查询确认用户 10 已消失。后两轮的用户恢复记录保存在 `artifacts/camera-permission-recovery/`。

随后用真实返回键复现了首次系统弹窗取消时误弹「需要系统权限」的问题。Android 在这次取消前后都不建议显示权限理由，但回调仍报告拒绝。Miru 现在保留发起请求时的理由状态，只有在一次拒绝让 Android 从建议显示理由转为不再建议时，才在当前进程记住永久拒绝并自动提供系统设置入口。系统授权后清除记录。上次进程留下的拒绝仍可从站点信息的「网站权限」进入 Android 设置。

2026-11-11，修复后的固定快照 `runtime-lifecycle-build-03` 通过编译，711 项输入无漂移，Lint 0 错误、29 警告。引擎 JVM 测试 133 项通过，1 项 Windows 符号链接测试跳过。主 APK SHA-256 为 `7afbbc8c9bb000d7b59107f560d8a254856d0c09ca507813ae76f9e68f7b3372`，测试 APK SHA-256 为 `4ea3c42a57d3a72f7610219f7becf3ba2802d4883356ad2ac2d4eab43df4e53b`。

API 35 x86_64 模拟器在各自的全新临时用户中执行，安装包的 SHA-256 和大小核对通过：

| 方法 | 结果与实际操作 |
|---|---|
| `cancellingAndroidDialogLeavesWebsiteChoiceAskAndAllowsAnotherRequest` | `runtime-lifecycle-cancel-final-01` 1/1，JUnit 31.991 秒。首次系统弹窗按返回，网页收到拒绝，网站仍为「询问」，没有设置提示。下一次实际弹窗可再次拒绝。 |
| `permanentAndroidDenialCanBeRecoveredThroughSettingsWithoutGrantingTheWebsite` | `runtime-lifecycle-recovery-final-03` 1/1，JUnit 36.698 秒。连续两次拒绝后从 Miru 打开 Android 设置授权，返回时网站仍为「询问」，在网站单独允许后取得实时视频轨道。 |
| `pendingAndroidCameraResultReturnsToItsOwnerAfterActivityRecreation` | `runtime-lifecycle-recreate-final-02` 1/1，JUnit 22.090 秒。在真实系统弹窗显示时重建 Activity，原请求和所属标签保持，系统与站点分别允许后取得实时视频轨道。 |
| `closingOriginalPageDiscardsLateAndroidGrantAndReleasesThePermissionSlot` | `runtime-lifecycle-late-final-05` 1/1，JUnit 21.147 秒。关闭原标签并打开同源新标签，再允许旧的系统摄像头请求。新标签仍须请求系统麦克风和网站摄像头/麦克风权限，网站阻止后页面收到拒绝。 |

保留修复前 `runtime-lifecycle-cancel-01` 的实际失败。修复前两次恢复测试分别在准备临时用户、拉回主包时因 ADB 连接中断，均未执行 instrumentation。修复后首轮重建和迟到结果也在 APK 核对时中断。两次已启动的恢复测试保留原失败，第一轮已打开 Android 应用设置但测试点击「权限」超时，第二轮在页面找不到请求按钮。`runtime-lifecycle-late-final-02` 关闭原标签后已完成旧的系统授权，确认新标签的网站权限仍为「询问」，随后新标签等待麦克风请求超时。各轮证据在 `artifacts/m3-cached-ranges/`，用户恢复记录在 `artifacts/camera-permission-recovery/`。

迟到结果的第三、第四次复测在执行测试方法前结束，第四次日志记录 Miru 在模拟器 CPU 和内存压力下启动超时。最后一次用相同模拟器资料、固定 APK 和测试方法，启动时临时为模拟器分配 6 GB 内存，复测通过。四个通过轮次均恢复了原用户并移除了临时用户。上述结果覆盖 API 35 模拟器中的普通摄像头请求，以及迟到结果后的组合请求。独立麦克风、定位的永久拒绝恢复和真机仍待验证。

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
