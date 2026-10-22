# M1 验证记录

日期：2026-10-22（Asia/Shanghai）。本记录仅标记实际运行的结果，不能以 fixture 或源码检查代替应用验收。

## 0.2.1 本轮结果

本轮继续 M1，**未完成整个 M1 验收**。保留下面 0.2.0 的历史失败记录，不用分批成功覆盖最终回归的失败。

- Release 构建、18 项 JVM 测试与 Debug Lint（0 errors / 14 warnings）通过，日志 `artifacts/m1-021/build-unit-lint.log`。fixture self-test 在扩大上限后也通过。
- 加入页面保护未确认警告前，17 项常规设备用例曾整套通过；加入警告后的全新 profile 回归为 **17 项中 16 项通过、1 项失败**：唯一失败为签名 uBO 首装超过 240 秒。原始结果 `artifacts/m1-021/final-suite-fresh-profile.log`、`final-device-results.xml`。不能将此报告为全绿。
- Gecko 配置只跳过该扩展可选的 AMO 描述 / 截图缓存查询，保留签名、blocklist、权限和更新校验。修改后独立首装有成功记录，但最终仍发生超时，根因和稳定性尚未解决。详见 `M1-ADBLOCK.md`。
- 最后独立重跑也在首装阶段超时（`artifacts/m1-021/adblock-warning-focused.log`）。本轮不能声称安装后的新警告断言已通过；失败快照确认 `pageNeedsReload=true`。原有测试只证明 popup session 创建，旧截图未呈现面板，不能作为面板视觉验收证据；已补充可见控件断言并编译通过，该新断言尚未跑到。
- 已去掉用 options-page 元数据推断就绪的逻辑，只接受真正的 native onReady。HTTP(S) 页面在原生扩展就绪前加载时保留刷新警告；安装完成本身不清除此警告。uBO 自己的 `!` 同样显示警告。
- 新增 Cookie 轮换用例通过：文件请求收到旧 Cookie 的 403 后，登录页设置新 Cookie，点击继续重新读取真实 Gecko Cookie 并完成正确字节下载。普通 / 无痕 jar 隔离仍通过。任意 Basic / Bearer 头嗅探未实现。
- APK 打开接入每应用未知来源授权设置页；**尚未验收系统安装器的实际授权和安装交互**。

### 500 MB 进程终止后续传：通过

真实应用在 API 35 上下载 **500,000,000 bytes**，记录断点后执行 `am force-stop app.miru.browser`，确认旧进程消失，再通过 Activity 启动恢复。验证阶段未重新 enqueue、未调用手动 resume；依赖前台启动自动恢复同一 Room 记录。进程外宿主 fixture 在两阶段持续运行。

- 杀进程前观测到的持久进度：4,345,910 bytes。
- 恢复后的真实 Range 包括 `bytes=570976-124999999`、`bytes=125257056-249999999`、`bytes=251316736-374999999`、`bytes=381505184-499999999`；均体现已保存的分段进度。
- MediaStore 输出长度为 500,000,000，SHA-256 为 `4e9cdcd8a9d475b31e8162ab9911260a2a7cbe4f18b467c64d92cee979b14085`，匹配 fixture。验证阶段耗时约 87 秒。
- 证据：`artifacts/m1-021/restart-prepare.log`、`restart-verify.log`、`restart-http-requests.json`、`restart-manifest.json`。
- 此项只证明进程终止 / 前台重启恢复，**不代表重启手机、掉电或网络切换已通过**。

### 已安装 uBO 的冷启动自动恢复页：本地 fixture 通过

准备阶段安装并预热默认过滤列表、保存 `/cold-page` 普通标签；然后 force-stop。验证阶段只启动 Activity，观测自动恢复页面，**没有先等待 uBO、手动导航或刷新**。恢复页面标题为 blocked，真实 badge > 0，服务端广告脚本请求计数保持不变。验证耗时约 7 秒，日志显示先完成 setup、随后收到真正 onReady。

证据：`artifacts/m1-021/cold-prepare.log`、`cold-verify.log`、`cold-http-requests.json`。该专项早于最后的页面警告补丁，覆盖默认 EasyList 本地规则；公网、完整网站矩阵和真机仍待验收，首次安装的放行不能计作通过。

### 分阶段测试复现

启动 `node tools/m1-fixture-server.mjs`，安装 debug APK 与 androidTest APK，保持应用数据和宿主 fixture 不变。分别使用 `adb shell am instrument -w -e class 类名#方法 -e restartPhase 阶段 app.miru.browser.test/androidx.test.runner.AndroidJUnitRunner`：

| 专项 | 准备方法 / 阶段 | force-stop 后验证方法 / 阶段 |
| --- | --- | --- |
| 500 MB | `DownloadRestartTest#prepareLargeDownload` / `prepare` | `DownloadRestartTest#verifyLargeDownloadAfterProcessDeath` / `verify` |
| 冷启动拦截 | `DownloadRestartTest#prepareProtectedSessionForColdStart` / `cold-prepare` | `DownloadRestartTest#restoredColdPageBlocksItsFirstAdRequest` / `cold-verify` |

类名需带前缀 `app.miru.browser.`；在两条 instrument 命令之间执行 `adb shell am force-stop app.miru.browser`。不要在阶段间卸载 / 清数据。常规 Gradle 套件用 `-Pandroid.testInstrumentationRunnerArguments.notClass=app.miru.browser.DownloadRestartTest` 排除这些手动阶段。

### 0.2.1 本地 APK

`app/build/outputs/apk/release/app-release.apk`：versionCode **3**、versionName **0.2.1**、minSdk 26 / targetSdk 37，仅 arm64-v8a。大小 **110,306,366 bytes**（约 105.2 MiB；十进制超过 110 MB，不能计为小于 110 MB 通过）。SHA-256：`978332bf564d6614d65afa5c7b3f1c845e53b28009bb72b6130b8352c433b52f`。本机无发布密钥，仍为调试签名，仅本地开发安装；arm64 真机运行尚未验收。

## 0.2.0 历史验证记录

以下为上一轮记录；500 MB、Cookie 轮换及本地冷启动专项的最新结果以上文为准。

## 环境与 fixture

- 隔离模拟器：`WfmAdApi35`，API 35，端口 5570，使用 `-read-only -no-snapshot -no-snapshot-save -no-window`，不修改已有 AVD 快照；用户现有的另一台模拟器不受影响。
- `node tools/m1-fixture-server.mjs` 启动只监听宿主回环地址的 HTTP fixture，默认端口 8744，模拟器可访问 `http://10.0.2.2:8744/`。
- `/download?bytes=5242880` 提供确定的 5 MiB 字节流；0.2.1 fixture 将上限扩展至 512 MiB。支持 HEAD、Range、If-Range、ETag、Last-Modified、416、UTF-8 Content-Disposition。
- `delay=40` 每 64 KiB 延迟 40 ms；`fail-once=1` 对每个初始 Range 主动截断一次；`ignore-range=1` 模拟不遵守 Range 的服务端。
- `/control?bump-etag=1` 同时改变 ETag **及响应内容**，可检测旧段错误拼接；`/control?reset=1` 重置。
- `/manifest?bytes=5242880` 返回长度、当前 ETag 与 SHA-256；`/requests` 记录方法、路径、Range、If-Range，不记录 Cookie 或授权头。
- 初始版本 5 MiB SHA-256：`5b45b55f2f91ea3e717e71beddeeb70f618e550dba2787ab1dcf6346fb3ef1f7`。

### 0.2.0 集成结果

本轮为 **0.2.0 M1 开发预览，未完成整个 M1 验收**。

- 18 项 JVM 测试全部通过：地址 5、引擎队列 3、uBO 元数据 2、下载逻辑 / 传输 8。
- 最后一轮整套设备测试 **15 项中 14 项通过、1 项失败**；唯一失败为 uBO 签名扩展首启安装在 240 秒内没有返回。浏览器本身仍可交互，扩展显示失败与重试，不伪报保护已开启。完整日志 `m1-final-validation.log`，原始 XML 留在 `artifacts/m1/full-device-results.xml`。
- uBO 独立设备测试曾两次通过（`m1-adblock-test.log`）：官方 XPI 安装 / 无痕许可、默认 EasyList 的真实拦截与 badge、全局停用后同一脚本到达服务端、重新启用后拦截、原版 action popup session。**这不能抵消最终套件的首启安装失败，也不能计为零漏拦验收通过。**
- 新增下载 UI 用例单独通过：真实下载、下载页、长按多选、仅删除记录、Snackbar 撤销与文件保留，报告 `artifacts/m1/download-ui-results.xml`。因此本轮分批设备用例中，15 项不同用例通过，uBO 首启稳定性 1 项仍失败。
- 下载页截图：`artifacts/m1/downloads.png`。
- Debug / arm64 release 构建通过。Debug Lint 为 0 errors / 14 warnings，release lintVital 通过。没有新增 lint baseline 来掩盖失败。

### 本地产物

- `app/build/outputs/apk/release/app-release.apk`：`app.miru.browser`，versionCode 2，versionName 0.2.0，minSdk 26 / targetSdk 37，仅 arm64-v8a。
- 大小 **110,301,478 bytes**（约 105.2 MiB，110.3 MB 十进制）；未将此数值直接计为规范“小于 110 MB”验收通过。
- SHA-256：`305fe68168922567b02d9132e6e68853865491ce1e525fbc086ec2830490f8fa`。
- 本机未配置发布密钥，release 使用 Gradle 的调试签名，仅供内部安装。未上传、发布或推送任何 APK。
- 设备测试运行的是 x86_64 debug 构建；arm64 release 已编译、裁剪和核对 manifest，尚未进行真机运行验收。

### 网络与安装边界

隔离模拟器使用默认网络，宿主系统另有本地代理配置。日志中可见 Gecko 远程设置 / add-on blocklist 获取失败；签名本地 XPI 的安装曾耗时约 74 秒和 189 秒，最后一次超过 240 秒。这个现象与网络环境相关，但本轮未完成独立网络归因，不能将失败全部归咎于测试环境。

自动审核两次拒绝了带宿主代理参数启动隔离模拟器的命令，未提供具体原因；没有改写系统网络配置或关闭签名 / blocklist 检查来获取通过结果。后续需要在网络可用的模拟器或真机上重跑首启与冷启动。

安装流程有 240 秒有界超时，超时后提供重试；网页等待扩展的门控仍是规范中的 1.5 秒。测试的最长等待只用于观测 Gecko 安装完成或失败，不改变产品的门控时长。

## Fixture 已运行

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| 模拟器启动 | 通过 | `emulator-5570` 的 `sys.boot_completed=1` |
| `node tools/m1-fixture-selftest.mjs` | 通过 | fixture 的 HEAD、四段拼接 SHA、变更 ETag / 字节、旧 If-Range 回 200、416、截断响应及重试；尚不代表 Miru 下载实现通过 |

## 设备用例

`app/src/androidTest/kotlin/app/miru/browser/DownloadIntegrationTest.kt` 使用测试进程内的回环 HTTP 服务，覆盖真实 DownloadCoordinator、前台服务、Room 断点与 MediaStore 输出：

1. 8 MiB 四段下载，进度落盘后暂停，继续请求从断点开始，最终 SHA 正确，唯一 Cookie 标记没有出现在数据库 / WAL 文件。
2. 下载部分内容后暂停，改变服务端 ETag 及每个字节，继续后输出必须与新内容 SHA 一致。
3. 服务端主动截断分段响应，自动重试后最终 SHA 正确。

这三个下载用例均已在 API 35 通过，HTTP 数据完全在设备回环服务内。它们不代表 500 MB 杀进程 / 重启手机验收已完成。

`AdblockIntegrationTest.kt` 的内容 fixture 只使用设备回环 HTTP，但 Gecko 的扩展安装阶段可能访问远程设置。规则准备后要求「脚本未到服务端 **且** 真实 badge > 0」，禁用后同一脚本必须成功执行；重新启用后再次阻断，并检查真实 action popup session。首次安装页若被 uBO 自身放行，必须观察到 `!` / `pageNeedsReload`，明确记录并刷新后再验证。这不是首屏零漏拦测试，也不代表 popup 全部控件或站点白名单已验收。

`CookieBridgeIntegrationTest.kt` 两项均通过：真实 Gecko Cookie 可用于受认证文件下载；无痕 jar 能实际接收自己的 Cookie，而普通续传头只读取普通 jar、不带无痕 Cookie。尚未观察任意 Basic / Bearer Authorization 请求头；其值若由下载调用方提供，仅保存在内存。

## uBO 离线验证方法

官方签名 uBO `1.75.0` XPI 内 `assets/thirdparties/easylist/easylist.txt` 含通用默认规则 `/ads/custom_ads.js$script`。fixture 的 `/adblock-default` 引用同源 `/ads/custom_ads.js`，可对比：

- uBO 启用并完成过滤列表加载：服务端没有该脚本 GET，uBO 的真实 badge / 记录显示拦截。
- 关闭全局拦截或当前站点加入白名单后刷新：服务端收到脚本 GET，页面显示脚本已执行。

不能仅以 `fetch.onerror` / 资源加载失败判断拦截，网络错误也会产生相同表现。fixture 同时提供 `/adblock` 及自定义静态规则演示，用于诊断控制链路，不应当计作默认列表测试。

## 尚未完成的验收

- uBO 首启安装稳定性、已安装版本升级、完整 popup / dashboard 操作、站点白名单、正常冷启动恢复页零漏拦仍需专项。首次安装的放行和 `!` 警告在实测中已出现，不得计为 A6 通过。
- 500 MB 杀进程续传、重启手机恢复、网络切换 / 等待网络、SAF、Android 8 / 9 存储路径与通知控制需专项验收。
- d3ward 与桌面 Firefox + uBO 的公网评分对比尚未运行。
