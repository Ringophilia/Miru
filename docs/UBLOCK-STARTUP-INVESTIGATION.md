# uBlock 启动超时：原因核对与当前包复测

日期：2026-11-04。按维护者第二组方向做有限调查；不扩展 P11/P2，不新增绕过签名、blocklist、TLS 或更新检查的处理。**本批未修改 App 安装/拦截逻辑，没有复现新的原生安装超时；补的是当前包验证和运行工具。**

## 历史问题并非同一条路径

1. **真正的原生安装等待。** `artifacts/m1-021/latest-install-logcat.txt` 在读取 manifest 后记录了 `Could not download addons-mlbf.bin`；下载失败后安装继续。结合固定版本的 Remote Settings 初始化路径，可以确认 MLBF 获取是历史安装等待的一个来源，不能据此把所有 240 秒超时唯一归因于这一次请求。现有包已原样携带同版官方初始资源，来源和完整性见 [blocklist 记录](M1-BLOCKLIST-VALIDATION.md)。本轮未追踪每一次原生 MLBF 文件读取。
2. **超时后的状态处理。** 旧日志中 240 秒超时之后还有真实 `onReady`。应用取消等待不保证尚未完成 manifest/blocklist 初始化的原生安装停止；旧逻辑可能保留失败或重叠重试。现有状态机保留原生操作槽，迟到的真实就绪继续完成配置，见 [生命周期修复](M1-STARTUP-LIFECYCLE.md)。
3. **已经配置完成却丢失就绪事件。** `artifacts/browser-core/057-benchmark-diagnostic-runtime.log` 的原始顺序为：14:39:03.715 创建 Runtime 并开始权限迁移；14:39:04.365 SDK 记录没有 `GeckoView:WebExtension:OnReady` 监听；14:39:04.600 迁移完成；14:39:04.737–742 才注册扩展 delegate；14:39:04.813 uBO 已安装、启用、允许无痕且 setup 完成，但 `readyVersion=false`。这是明确的监听窗口缺口，不能把 metadata 完成当成真实就绪。现有早期监听和真实事件交接见 [启动就绪记录](M1-057-STARTUP-VALIDATION.md)。

因此排查时要区分 `nativePending=true/phase=INSTALLING` 的安装等待，与 `setup=true/nativePending=false/readyVersion=false` 的就绪观察问题；1.5 秒页面等待门也不是 240 秒安装 watchdog。没有为复测修改这些期限。

## 当前包的三种条件

使用同一固定主包，来自 `ab418dd` 的日期/颜色功能版本，versionName 仍为 0.5.21/code29。主 APK SHA-256：`d5bea18dcbb366fb444da94b13e53f387209d4359f4d3bc67b5ab08d5d87fa59`。本批只更新测试 APK，使已有广告拦截测试能通过正常按钮跳过首次引导；没有预置 Gecko 或扩展状态。

设备为 API35/x86_64 的 emulator-5554，使用独立、临时 Android 测试用户，原用户 0 的应用资料保留。安装、包路径查询和 instrumentation 都显式传入相同用户 ID，安装后以临时 pull 验证 APK SHA/大小，随后删除 pull。

| 条件 | 结果 | 原始运行 | 安装/就绪观察 |
| --- | --- | --- | --- |
| 新用户 10、联网首装 | 1/1；JUnit17.455秒，runner22.871秒 | `artifacts/m3-cached-ranges/ublock-fresh-online-01/` | `found=false`，setup 在 driver 开始后576ms完成，首次真实 ready 约1052ms；同进程 Gecko 日志确认 `UP, WIFI, WIFI` |
| 用户10保留资料、强停后冷启动 | 1/1；JUnit13.390秒，runner15.594秒 | `ublock-retained-cold-01/` | `found=true`，setup 73ms，真实 ready 约92ms |
| 新用户12、飞行模式且无活动网络的首装 | 1/1；JUnit13.948秒，runner18.143秒 | `ublock-fresh-offline-02/` | 测试内双断言 `airplaneMode=1/activeNetwork=null`；`found=false`，setup385ms，真实 ready 约651ms |

这些时间从安装 driver 开始算，不是完整应用冷启动或真手机性能成绩。测试里的 ready 等待仅1/0/0ms，因为它在打开首个受控页面之后才开始，启动过程此前已与页面打开并行；不能拿该等待值当安装耗时。

三轮都要求真正 READY、版本/启用/无痕授权状态有效、真实默认规则使本地广告请求不达来源且 badge 大于0、关闭拦截后同一路径可达、再次启用后重新阻断，并打开实际 uBO 面板。三轮的早期测试页面均实际被阻断；这不代表全部网站、全部启动时机零漏拦。

本轮冷启动的 ready 都在正常 delegate 注册后到达，没有再次实际触发早期交接分支。历史日志和状态单测分别保留其证据边界。没有发现当前三轮的原生超时，不能为了“找一个新根因”改掉已经有效的处理。

## 工具和环境恢复

- 40项安装状态、早期就绪交接和页面警告 JVM 测试通过。设备测试仍是一个现有广告拦截测试类，在三种条件各运行一次，没有新增巨大用例矩阵。
- `run-device-validation.ps1 -UserId` 现在把指定用户用于安装、`pm path` 和 instrumentation；支持已有测试的带点参数 `miru.requireNoNetwork`。5项宿主检查覆盖默认/显式用户、带点参数、错误安装字节、错误结果数量和传输中断的处理。
- 首轮在线/冷启动外层脚本在切回原用户后，与系统自动删除临时用户发生竞态，退出报错；两份设备结果各1/1保留，之后实际查询确认用户已移除。第一次离线外层运行被参数名校验拒绝，未启动设备测试，不计作 uBO 安装失败。修正工具后真实离线轮独立通过。
- 最终仅剩原用户0，前台也已恢复为0。飞行模式/Wi-Fi/移动数据前后均为0/1/1。网络与临时用户恢复的原始记录在 `artifacts/ublock-startup/batch-01/final-user-verification.json`、`network2-before.json`、`network2-after.json`。
- 官方 uBO XPI SHA仍为 `5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287`；Gecko资源包 SHA仍为 `f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`，独立核对结果见该目录 `package-pins.json`。没有替换官方扩展或安全资源。

具体命令和输入在上述运行的 `input.json`、`runner.ps1` 及 batch 目录的准备脚本/启动日志中保留。临时用户在测试后已经删除；复跑应新建受控测试用户和运行名称，不能把历史用户ID当作仍存在。APK按保留策略裁剪，历史输入是否仍在应查清理账本。

## 保留的缺口

本批没有升级版本、没有跑完整常规回归、没有打新 ARM64 包、没有真机运行。第一组160/163的失败不被这三项抵消。当前受控联网/离线首装和一次冷启动通过，不等于全部弱网、多轮、升级、原生进程崩溃、真实网站或真手机兼容通过；d3ward/桌面同规则比较、完整首屏零漏拦和完整M1验收仍未完成。

下一步继续站点通知等剩余网页权限的功能闭环；不将 B13 或 A1/A6 整项提升为全量验收。
