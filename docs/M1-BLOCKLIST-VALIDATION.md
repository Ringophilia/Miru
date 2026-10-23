# M1：官方 MLBF 初始资源与构建验收

日期：2026-10-23。**本批已完成来源、确定性生成和最终 debug APK 资源完整性验证；尚未证明修复 uBO 首装超时。** 新 profile 的离线首装、在线首装／更新、冷启动拦截仍待 root 执行运行时验收。本报告的包级通过不能替代这些结果。

## 问题证据链与归因范围

1. 历史设备整套回归有签名 uBO 首装超过 240 秒的失败，见 [M1-VALIDATION.md](M1-VALIDATION.md)、[M2-VALIDATION.md](M2-VALIDATION.md)。历史超时后 genuine `onReady` 的生命周期问题另见 [M1-STARTUP-LIFECYCLE.md](M1-STARTUP-LIFECYCLE.md)，不由本批资源测试覆盖。
2. 原始日志 `artifacts/m1-021/latest-install-logcat.txt` 中，10:51:35 已开始读取扩展 manifest，10:52:48.400 记录 `Could not download addons-mlbf.bin`，随后扩展初始化继续。它支持 MLBF 下载是至少一段安装等待的来源；不能据此将所有 240 秒超时归结为唯一网络请求，也不能推断新增资源后已经消除等待。
3. 当前 Miru 通过 `installWebExtension` 安装本地、官方签名的普通 uBO XPI。固定版本 Gecko 的 [`XPIInstall.sys.mjs`](https://hg.mozilla.org/releases/mozilla-release/file/e4ce606692cd81e6898547e2edd7c552fefcbb04/toolkit/mozapps/extensions/internal/XPIInstall.sys.mjs) 在普通扩展 manifest 安装路径等待 `addon.updateBlocklistState()`；[`Blocklist.sys.mjs`](https://hg.mozilla.org/releases/mozilla-release/file/e4ce606692cd81e6898547e2edd7c552fefcbb04/toolkit/mozapps/extensions/Blocklist.sys.mjs) 的 MLBF 获取使用 attachment cache／dump fallback。这提供源码上的安装依赖链。
4. 实际固定 AAR 的 `omni.ja` 含 4,079 个条目，缺少下表五个 add-on MLBF 初始资源，但原 `defaults/settings/last_modified.json` 已包含 `blocklists/addons-bloomfilters: 1792413409072`。
5. 同 revision 官方 [`services/settings/dumps/blocklists/moz.build`](https://hg.mozilla.org/releases/mozilla-release/raw-file/e4ce606692cd81e6898547e2edd7c552fefcbb04/services/settings/dumps/blocklists/moz.build) 明确将这五项排除在 `mobile/` 构建外。这解释了原 Android 包没有对应 dump；没有本地数据的初始化会进入 Remote Settings 获取路径。

因此本批只为固定 Gecko 构建补入同 revision、原始字节的官方初始资源，恢复 Gecko 已有的 dump fallback 入口。是否在实际安装时命中该入口，以及其他远程依赖是否仍造成等待，需要运行时证据。

## 固定输入与保持范围

原依赖为 `org.mozilla.geckoview:geckoview-omni:158.0.20261021001945`，Gecko 应用版本 `158.0.2`，源码 revision [`e4ce606692cd81e6898547e2edd7c552fefcbb04`](https://hg.mozilla.org/releases/mozilla-release/rev/e4ce606692cd81e6898547e2edd7c552fefcbb04)。AAR 的 POM 与原 omni 中 `AppConstants.sys.mjs` 对应这一 revision。

| 输入／原资源 | 长度 | SHA-256 |
| --- | ---: | --- |
| 原官方 AAR | 243,952,420 bytes | `a3af9d5ffb1f898df5b8782d083b69c4274c4f3275e31f3dac33d0d2aa16c692` |
| 原 `assets/omni.ja` | 4,079 entries | `b6dca3074e505b3d475504c864ba517138e383d6d9f23c6db94de34403c254b5` |
| 原 `defaults/settings/last_modified.json` | 1,444 bytes | `56ff287f7e6e41e30c0a766ed3073e8d2752aca8784960f9001a4f324d6c8f0d` |

原 AAR 和 Gradle dependency cache 不被改写。生成器只读取原件，向 app 的 generated assets 输出新的 `omni.ja`。所有原条目的 payload、长度和压缩方式保留，原 index 不变；ZIP 容器重新排序、固定时间戳并重新压缩，所以不声称新旧整个 omni 文件 SHA 相同。任何已有资源与新增路径冲突都会拒绝生成。

新增路径均位于 `defaults/settings/`，下表相对路径也是 `third_party/mozilla-blocklist/snapshot/` 内的相对路径：

| 新增官方资源 | 长度 | SHA-256 |
| --- | ---: | --- |
| `blocklists/addons-bloomfilters.json` | 72,903 | `43dbbe38a70b3350ef8eaf5c2f2f7e300196b7986d12cc639b058eef966ef86a` |
| `blocklists/addons-bloomfilters/addons-mlbf.bin` | 938,855 | `bf59d309000ab016e6fcfb558b2ba61ba1368ad4a4a2de424f9f5012a5e47b5a` |
| `blocklists/addons-bloomfilters/addons-mlbf.bin.meta.json` | 500 | `81078ea0f4e5fd2ee36bd58c44849898d2a12e469ba494af30d22895ce216135` |
| `blocklists/addons-bloomfilters/softblocks-addons-mlbf.bin` | 218,202 | `d778ccd02b613efb8c7ee0d4e4517dcac61b7e12a362dc600890c6c3065772cb` |
| `blocklists/addons-bloomfilters/softblocks-addons-mlbf.bin.meta.json` | 511 | `c715336c8f9ba1c8845b947cba80d4cef6a689d1d4f4408471287b98c6eca36a` |

五份文件不改 JSON 字段或换行。collection `timestamp` 与原 index 都为 `1792413409072`；两份附件 metadata 与 collection 内完整 record 一致，附件 hash／size／type／key format 交叉验证。来源、官方 CDN 复核、MPL 原文及对应源码获取方式见 [第三方来源记录](../third_party/mozilla-blocklist/README.md)。HG collection／soft MLBF 与两份 CDN MLBF 的重下载匹配 SHA；部分 HG 路径重查传输超时已在来源记录中保留，未接受替代内容。

本批没有修改 Gecko 执行脚本、签名／blocklist／更新检查、TLS 或安全偏好。此前仅跳过该扩展可选 AMO 描述／截图缓存查询的固定 `RuntimeConfiguration` 保持原状；它不是离线安装已通过的证据。

## 构建接线与独立检查

`buildSrc/build.gradle.kts` 使用 JDK 21、Gradle API 与 local Groovy 编译 `tools/gecko-blocklist-buildlogic/src/main/java`。`app/build.gradle.kts` 对每个实际 variant 的 `RuntimeClasspath` 建立 `artifactType=aar` view，按真实选中的 module／version 筛选固定 `geckoview-omni`，交由任务再次验证原件长度与 SHA。更新版本、缺少／多个匹配或重打包原件会失败。

`GenerateGeckoBlocklistAssets` 输出 `app/build/generated/gecko-blocklist/<variant>/omni.ja`，由 AGP generated assets API 加入 app 层，覆盖依赖 AAR 的同名 asset。实际合并结果已从 APK 验证，而非仅凭 task 注册或文件存在断言。`artifacts/m3-quality/commit-validation-build.log` 记录 `4079 original resources preserved, 5 official resources added` 且构建成功；后续 `notification-identity-build.log` 构建成功并复用该生成任务。

独立验证没有运行项目 Gradle或设备，用已安装 Gradle distribution 的 API／Groovy jars 配合 JDK `21.0.12` 的 `javac`／`java`：

```powershell
./tools/gecko-blocklist-buildlogic/run-tests.ps1 `
  -GeckoAar '<官方缓存中的 geckoview-omni-158.0.20261021001945.aar>' `
  -GradleHome '<已安装 gradle-9.3.1 目录>' `
  -JdkHome '<已安装 JDK 21 目录>'
```

终态为 **36 checks 通过**，不是 36 项设备用例：

- 两次完整输出字节完全一致，16,459,034 bytes，SHA-256 `65becbcb9be64a1d50f84248e7a6d95531a15dfab25514dff631dcce3e358939`。
- 独立 `ZipFile`／digest 实现比较 4,079 原 payload／压缩方式、原 index，以及恰好五项原始 additions；不依赖生产 verifier 作为唯一依据。
- wrong version、wrong length／same-length corrupt AAR、缺失／额外／同长度损坏 snapshot、非法 JSON、timestamp／metadata／附件 hash／size／type／key format／location 异常、危险 ZIP 路径均拒绝。
- 失败输入保留上一份有效输出，临时提取目录清理完成。

证据为 `artifacts/m1-blocklist/standalone-compile.log`、`standalone-tests.log`，以及 4,079 条 payload 的 `standalone-work/original-resource-sha256.tsv`。该 TSV SHA-256 为 `5d9cc6c36fe7730229d84ab366b997157f54b2441a1046cf3103df7a3f87518e`。

## 最终 debug APK 资源验证

本报告最新包快照是 `app/build/outputs/apk/debug/app-debug.apk`，修改时间 `2026-10-23T13:06:08.2866535Z`（上海时间 21:06:08），长度 **473,786,644 bytes**，SHA-256：

```text
289d8f43f4f5c8d28a3be81886cc61aade85ee7f9d76c5d9ca72daadf1734a24
```

只读验证命令：

```powershell
./tools/gecko-blocklist-buildlogic/verify-apk.ps1 `
  -Apk app/build/outputs/apk/debug/app-debug.apk `
  -MinimumApkWriteTimeUtc '2026-10-23T13:06:08Z'
```

`artifacts/m1-blocklist/verify-apk-summary.json` 终态 **PASSED**：APK 中恰好一个 `assets/omni.ja`，其 SHA 等于上述生成文件；内部 4,084 个路径无重复；4,079 原 payload／长度逐项匹配独立 TSV；差集恰为五份固定 snapshot，逐份与原文件和固定 pins 一致；原 `last_modified.json` 不变。verifier 支持流式 SHA，读取打包后的普通 ZIP，不对原 optimized omni 做未经验证的 .NET 解析。

错误的预期 omni SHA 和晚于包修改时间的 build cutoff 已分别确认被拒绝，见 `verify-apk-negative-sha.json`／`verify-apk-negative-stale.json`。这些负例结果为预期失败，不能误报成实际 APK 失败。

上述大体积 debug 包包含测试 ABI 和调试内容，不用于声称 release 大小或发行签名验收通过。后续重建 APK 后需要重新验证对应新包，不能沿用本快照 SHA。

## 首轮真实离线运行（失败保留）

`fresh-offline-device.log` 在专用 API35 模拟器清除合成应用资料后执行，测试断言飞行模式为 1、`activeNetwork == null`。真实状态在测试开始后 **3,170 ms** 达到 READY（开始等待时为 INSTALLING，等待 38 ms），见 `fresh-offline-startup.log`。这证明本轮没有再等 240 秒，不能据此认定整个首装拦截已通过。

该轮整体 **1 项失败，10.566 秒**：受保护页面的全局广告计数期望 1、实际 2。旧 fixture 的所有阶段共用页面标题与总计数，而 `newTab()` 的选中快照异步更新，页面等待可能命中上一个页面。后续测试改为独立 case、精确 tab ID / URL / 完成标题和按 case 的来源计数；保留同一默认广告路径、真实 badge / popup / 开关断言及原有时间门槛。修正后的结果须另存日志，不能覆盖此失败记录。

第二轮 `fresh-offline-isolated-device.log` 已走过各 case 的实际拦截和全局开关，最终在新增“其他阶段不得改变首个 case 请求数”的断言失败（13.475 秒）。生产 `BrowserController.setAdblockEnabled()` 明确传入 `::reload`；禁用会重载当前受保护页面并允许请求，故该新增跨阶段断言与预期行为冲突。仅移除此新增断言，保留按 case 的即时阻断、禁用后请求可达和重启用后零请求检查；没有调整生产行为或等待期限。

## 最终受控运行结果

`isolated-fixture-build.log` 构建成功（28 秒）。针对同一 Debug APK，每轮先清除专用模拟器内的合成应用资料，再执行真实签名扩展安装：

| 条件 | 结果 | 原始日志 | READY 等待 / 测试开始至 READY |
| --- | --- | --- | --- |
| 飞行模式 1、activeNetwork=null（测试内双断言） | **1/1 通过，16.056 秒** | `fresh-offline-final-device.log` / `fresh-offline-final-startup.log` | 448 ms / 3,653 ms |
| 开启网络，外部确认 Active default network:108 | **1/1 通过，18.264 秒** | `fresh-online-final-device.log` / `fresh-online-final-startup.log` / `fresh-online-network.txt` | 420 ms / 3,377 ms |

测试均要求官方扩展实际 READY、版本与私密授权有效、独立页面广告请求不达来源且 badge>0、禁用同一路径可达、重新启用再次阻断，并打开真实 popup。上述 READY 时间从测试方法开始计算，不是完整应用冷启动时间。已恢复模拟器原有网络设置。

这证明此 APK 的包内资源完整性及上述两轮 fresh-profile 行为；未单独追踪 MLBF 文件读取，也不能仅据两轮结果将历史超时及全部首屏零漏拦要求划为解决。首次 uBO 规则初始化仍允许加载并保留刷新提示。

## 尚待运行时验收

以下项目在本报告中保持 **待验证**，由 root 继续执行并补原始证据：

- 全新 Gecko profile 多轮首装、不同网络与真机稳定性；1.5 秒页面门控与 240 秒 watchdog 各超时分支的专项。
- 直接观察 Gecko 对新增 dump / MLBF 的读取、与未覆盖资源的同条件比较；两轮运行结果不能代替读取追踪或因果对照。
- 冷启动恢复页面的首批广告请求、开启／禁用与私密窗口行为；规则初始化与播放器等其他模块的回归。
- 在线 Remote Settings 更新和失败／恢复路径仍正常，不依赖关闭签名、blocklist、TLS 或更新校验。

本批最终两轮结果如上，先前失败继续保留；不把“打包通过”或 READY 较快写成“首装已全面修复”。
