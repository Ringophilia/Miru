# 第二组网页权限与 uBlock 检查点（0.5.22）

检查点使用 `e386314` 的 0.5.22/code30 源码，按 [AGENTS.md](../AGENTS.md) 执行。Debug 与 ARM64 Release 的 711 项源码输入一致，构建期间无漂移。本站数据清理按维护者决定保存在 `codex/site-data-wip`。P11 和 P2 冻结。

## 构建与 ARM64 包

- `artifacts/m3-cached-ranges/browser-permissions-checkpoint-debug-01/` 固定 Debug 构建耗时 2 分 57 秒。主 APK SHA-256 为 `92bbc0441faca342453923808ae9eb375887524d117effa9da404fe1c55ad2d9`，测试 APK 为 `3a9abf50d2ca6c439453ad66fe06d5ede5eea5466db893ae3f20ee8efbcc24f7`。Debug Lint 0 错误、29 警告。
- `artifacts/m3-cached-ranges/browser-permissions-checkpoint-release-01/` 的 R8、资源缩减和 Release Lint 成功，构建耗时 4 分 31 秒，Lint 0 错误、29 警告。全模块 JVM 任务复用相同输入的 Gradle 报告，288 项通过、1 项因 Windows 符号链接权限跳过，零失败。完整报告随构建保留。
- 最终开发包为 `artifacts/releases/Miru-0.5.22-arm64.apk`，108,595,529 字节，小于 110,000,000 字节。SHA-256 为 `8d34cc74306c17000354c2caeb035a73d5c021784ff5f1c5ce07e5fd8945c358`。原 R8 输入 111,726,918 字节，SHA-256 为 `161ddc160d956989dcf6bb94e314f6ca440237ed2e5d3dd1cb7310930b6f80de`。
- 优化工具复用已完成的 0.5.21 包的压缩内容，核对了全部 1549 项非签名 ZIP 内容的路径、长度、CRC、SHA-256 与压缩方法。16 KB 对齐、同一 Android Debug 证书签名、17 个 ELF64/AArch64 库，以及 Gecko 的 4077 项原资源、两项固定补丁与五项增补均通过。证据在 `artifacts/releases/Miru-0.5.22-arm64.apk.verification-20261111-134918-1fa16e3c/` 与构建目录中。正式签名、ARM64 真机运行和本版相同 DEX 的 x86 Release 运行仍待验证。

## 第二组已有设备证据

本节保留各功能批次自己的固定包与设备范围。

- 弹窗拦截及网站允许入口、拉起外部 App 的确认、网页表单选择与日期时间及颜色输入，分别验证了真实网页回调、旧页面动作失效和相关界面重建。[功能批次记录](STATUS.md)
- 网站通知覆盖系统通知、拒绝 Android 权限后保存网站选择及重新授权恢复。按网站设置自动播放覆盖三档和手动播放。[网站通知](B13-SITE-NOTIFICATIONS.md) · [自动播放](B13-AUTOPLAY.md)
- 站点信息可以查看当前网站证书。客户端证书请求验证了取消，以及实际选中公开 RSA 测试身份后完成 BadSSL 网站认证。受保护内容许可验证了 Widevine 媒体密钥的创建与绑定。[查看证书](B13-CERTIFICATES.md) · [客户端证书](B13-CLIENT-CERTIFICATES.md) · [受保护内容](B13-PROTECTED-MEDIA.md)
- 普通摄像头请求已在 API 35 模拟器上验证真实系统取消、永久拒绝后设置恢复、系统弹窗显示时重建 Activity，以及关闭原标签后的迟到结果。[系统权限](B13-RUNTIME-PERMISSIONS-TEST.md)
- uBlock 历史超时包含扩展安全规则库下载等待、原生安装迟到后界面状态未恢复、权限迁移前后就绪监听交接。独立临时用户中的联网首装、离线首装、保留资料的冷启动各通过一项真实拦截专项。弱网、升级和真机范围仍待验证。[uBlock 首装调查](UBLOCK-STARTUP-INVESTIGATION.md)

## 常规回归环境与命令

使用 API 35 x86_64 模拟器 `emulator-5554`，同一 AVD 以只读方式启动，6 GB 内存。前两轮用宿主 NVIDIA GeForce RTX 3080 Ti Laptop GPU，第三轮重启到 Google SwiftShader。各自的启动命令、渲染器和用户 0 通知授权记录在 `artifacts/browser-permissions-checkpoint/emulator/` 与 `emulator-swiftshader/`。

常规套件排除四个独立前置类 `DownloadRestartTest`、`OfflineRestartTest`、`RuntimeSitePermissionIntegrationTest`、`AudioCaptureIntegrationTest` 和 `app.miru.browser.capability` 包。

```powershell
./tools/build-player-validation.ps1 -BuildName browser-permissions-checkpoint-debug-01
./gradlew.bat testDebugUnitTest :core:common:test :app:assembleRelease :app:lintRelease --no-daemon --console=plain
./tools/run-device-validation.ps1 -App -Routine -BuildName browser-permissions-checkpoint-debug-01 `
    -RunName browser-permissions-checkpoint-routine-01 -Expected 194
./tools/clean-validation-artifacts.ps1 -Apply
```

SDK 与 JDK 路径、完整 Release 命令、签名和资源检查命令见固定构建目录。历史二进制可能按保留策略裁剪，日志、源码清单和哈希继续保留。

## 常规回归结果

`browser-permissions-checkpoint-routine-01` 实际开始了 194 项中的 70 项，55 项通过、14 项失败。第 70 项单文件上传进行中，Android 因主界面没有及时响应焦点事件而终止测试。共 125 项没有完成结果，整套回归未通过。运行器 497.738 秒。保留 `runner.log`、`logcat.log` 及 `artifacts/browser-permissions-checkpoint/routine-01-lastanr.log`。

已结束的失败包括浏览器 Cookie 和图片复制相关三项、图片复制策略七项、Cookie 下载与登录刷新三项，以及停源后的缓存拖动显示一项。早期扩展日志记录了图片策略查询不可用。用同一固定包重启测试进程、沿用模拟器资料隔离复测 Cookie 类，`browser-permissions-checkpoint-cookie-retry-01` 为 0/3，运行器 63.307 秒。三个用例均未能通过原生桥接读取已经准备的普通站点 Cookie。这个结果不能单独判断 Gecko 自身的 Cookie 存储状态。

`browser-permissions-checkpoint-routine-02` 沿用相同的固定包和宿主 GPU，已结束的用例为 69 项通过、1 项失败，运行器 743.049 秒。首轮的 Cookie、图片复制和单文件上传在本轮通过。唯一结束为失败的用例是 DASH 缓存源未显示预期的蓝帧。第 71 项上传取消时 Android 系统崩溃，共 124 项没有完成结果。系统 watchdog 日志记录 `android.ui` 卡在 `BLASTBufferQueue.nativeCreate` 71 秒，随后系统进程被终止。保留本轮完整结果、`logcat.log` 和有实际桥接连接的早期日志 `artifacts/browser-permissions-checkpoint/retry-early-extension-logcat.log`。同包通过与失败并存，桥接失效的原因仍需定位。

第三轮 `browser-permissions-checkpoint-routine-03` 沿用同一固定 APK、194 项常规测试及原有断言，改用 SwiftShader。完整跑完，**171 项通过、23 项失败、零跳过**，JUnit 1567.034 秒，运行器 1570.597 秒。安装后的主包与测试包均核对过 SHA-256 和大小，临时拉回的 APK 已清除。

| 失败范围 | 项数 | 实际表现 |
| --- | ---: | --- |
| 浏览器核心与图片复制策略 | 10 | 读取 Cookie 或复制图片的测试失败，图片策略日志显示查询不可用。 |
| Cookie 桥接 | 3 | 已准备的普通站点 Cookie 未能由桥接读回，登录刷新及认证下载用例无法继续。 |
| 网页媒体与离线下载入口 | 6 | 视频嗅探、清晰度探测、无痕视频下载确认、网页长按倍速和自动接管等用例超时。视频嗅探日志中，本地服务已收到媒体请求，但浏览器未报告媒体条目。 |
| 无痕 Cookie 与表单清理 | 2 | 原有无痕 Cookie 验证和准备清理时并发开标签的用例失败。 |
| MP4 磁盘缓存播放 | 2 | 两项已存在的缓存播放测试超时。保留结果，P11继续冻结。 |

第二组新增常规用例 31/31 通过，包含弹窗、外部链接、表单选择和日期/颜色、通知、自动播放、证书查看及受保护内容。uBlock 常规拦截 1/1 通过。第一组曾失败的历史图标与超分用例在本轮通过，图标类 2/2，超分基准类 2/2，超分渲染类 3/3。各轮失败均保留，能力专项及前置类仍按各自结果记录。

宿主 GPU 的第二轮确实连接了内置桥接，浏览器核心 7/7、图片策略 9/9、Cookie 桥接 3/3 均通过。SwiftShader 第三轮再次出现对应失败，早期日志在 `artifacts/browser-permissions-checkpoint/emulator-swiftshader/routine-03-early-extension-logcat.log`。现有证据显示启动结果有差异，桥接失效的原因尚未证实。接下来优先复现桥接连接建立的过程，恢复登录态下载、网页图片复制与视频识别，再推进第三组。
