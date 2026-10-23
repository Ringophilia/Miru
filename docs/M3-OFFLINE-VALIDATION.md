# M3 视频离线：0.4.1

日期：2026-10-23（Asia/Shanghai）。本批对应规范 §8.5.3、P12 与 D6，不将它等同于 M3 或整个浏览器验收完成。完整需求仍以 [验收矩阵](ACCEPTANCE.md) 为准。

## 实现

- 新模块 `:feature:offline` 使用 Media3 1.11.1 DownloadManager / DownloadService；HLS / DASH 在准备阶段读取真实轨道，按用户选择的清晰度保存，并包含关联的音轨与字幕。直播、已知 DRM、检测到 DRM 的轨道不加入下载，普通 MP4 继续走现有分段下载引擎。
- `filesDir/offline` 使用独立的 SimpleCache / NoOpCacheEvictor，与普通播放的 LRU 缓存分开。每个下载 UUID 都有独立缓存键前缀，删除一个副本不影响另一个；离线播放器的数据源没有 upstream，清单、初始化段和媒体分片缺失时本地报错，不回源联网。
- 媒体面板提供下载入口与清晰度 / 名称确认；下载页的全部 / 视频分类统一显示两种后端，提供搜索、日期、多选、重命名、暂停 / 恢复 / 取消、删除和六秒撤销。离线缓存没有直接可分享的文件，不暴露尚未实现的 MP4 导出入口。
- 无痕离线需要明确确认保留视频与下载记录；其来源页不写入 Media3 索引，请求头只保留在进程内存。完成后的无痕离线仍以 FLAG_SECURE 播放，不读取或写入普通播放进度。
- 普通认证续传可从当前普通网页 / Gecko Cookie 桥恢复；需要 Authorization 的任务单独检查该字段。私密任务在进程丢失凭据后不读取普通 Cookie，提示返回网页。跨来源（含端口改变）移除 Cookie / Authorization，禁止 HTTPS 降级。
- 两套引擎共用 `DownloadConcurrency.transfers` 的 1–5 个文件传输预算。降低上限时保留现有传输，阻止新传输进入，分段线程不另外占一个文件名额；取消等待会释放占位。暂停保留缓存，取消在最后一个缓存 sink 关闭后再清理一次，避免尾部残留。
- 前台通知提供控制，完成通知携带 UUID 打开应用，由持久索引恢复离线播放；不把过期的内存播放 token 放进通知。

## 发现与修复

首轮 6 项设备专项中，DASH 的断源完整播放通过，其余 5 项 HLS 路径在 prepare 阶段失败（`artifacts/m3-offline/initial-device.log`）。原因已与固定版本 Media3 源码核对：`DefaultMediaSourceFactory` 对非空 MIME 精确匹配，`application/vnd.apple.mpegurl` 被当作 Progressive 内容。准备入口现统一为 Media3 canonical MIME；新增单元测试覆盖 HLS 别名、大小写、参数、路径后备和直接文件拒绝。

第二轮 8 项专项为 7 / 8：统一列表的测试错误地在 unmerged 语义树中要求按钮点击与其子图标描述处于同一节点。保存两种真实语义树核对后改用 merged 匹配；相同重命名、混合删除撤销和播放操作单项通过（`ui-retry.log`，21.895 秒）。没有删去操作或弱化断言。

复核还修复了取消与完成竞争：界面状态可能比原生索引慢，单项取消先查询原生完成状态；已完成则保留，已经接受的取消则以 UUID 标记持久收敛，后续竞争完成不发完成通知、不重新提供播放。新增测试暂缓应用主线程回调，令原生索引先完成再点击取消，验证视频仍可完整播放。

重启专项曾在实际播放后因测试清理失败：测试发送的新 Intent 丢失了 ActivityScenario 跟踪所需的 action / categories。现复制原 Intent 并清除测试规则的 NEW_TASK / CLEAR_TASK，保留真正的 `onNewIntent` 路由与全片断言；不修改产品路由来迁就测试。失败记录保留为 `restart-verify.log`。

## 验证范围

`OfflinePlaybackIntegrationTest` 使用真实 HTTP / DownloadManager / 磁盘缓存 / PlayerActivity Surface，覆盖：

- 160×90 / 320×180 两档 HLS，选择低档后不下载高档媒体分片，下载双音轨与 WebVTT。
- 关闭源服务后 HLS / DASH 从头完整播放到结束，包含 seek、音轨 / 字幕切换。
- 慢响应暂停与恢复；同 URL 两个 UUID 独立下载，删除一个副本及清除普通播放缓存后另一个仍可离线播放。
- 跨来源私密请求头隔离；扫描离线目录、索引与相关偏好，无 fixture Cookie / Authorization 原值。
- 直播 / DRM / 直接文件拒绝；取消慢分片后缓存归零并保持稳定、不继续发请求。

`OfflineDownloadsUiIntegrationTest` 通过真实两种下载后端验证统一列表、分类 / 搜索 / 重命名、混合多选删除 / 撤销，以及停止源服务后点击离线项播放。`OfflineMediaPanelIntegrationTest` 从真实私密 Gecko 页面进入媒体下载，验证画质选择、FLAG_SECURE、未确认前禁用开始，以及持久索引不含私密来源页。

`OfflineRestartTest` 是独立的两阶段专项：先完成真实下载并保存 fixture UUID / seed PID，外部 force-stop，再从新进程通过 MainActivity 的通知同款 UUID Intent 打开并完整播放。它不证明正在下载时的进程终止续传或手机重启，两种范围不混算。

最终两阶段分别通过（3.282 / 18.096 秒，`restart-prepare-delivery.log` / `restart-verify-delivery.log`）。第二阶段传入 `miru.requireNoNetwork=true`，测试同时断言系统飞行模式开启和 `ConnectivityManager.activeNetwork == null`；外部状态记录为 enabled / Active default network: none（`delivery-airplane-state.txt`）。已恢复此隔离模拟器的飞行模式、Wi-Fi和移动数据设置。

## 可复现素材

`tools/generate-offline-fixture.ps1` 生成 `app/src/androidTest/assets/media/m3-offline/` 的合成 160×90 HLS，复用既有 320×180 / 双 AAC / WebVTT 与 DASH 合成素材；不含外部影视内容，只进入测试 APK。断网播放采用关闭服务端、upstream-free 数据源和上述模拟器无默认网络专项，尚不表述为真机飞行模式验收。

## 尚待完成

- 真机网络切换、飞行模式、重启手机、长时间后台、通知栏实际点击与 Android 后台限制矩阵。
- 下载进行中进程终止 / 恢复，Cookie / Authorization 轮换和私密重新认证的完整设备专项。
- P2 离线导出 MP4 需按 R6 先验证 Transformer；缩略图、超分与其他 M3 / M4 项继续推进。
- ARM64 真机、公网站点、性能 / 包体 / 正式签名和分发许可门继续保留。

## 最终回归与产物

- 最终常规设备回归 **47 / 47 通过**（202.783 秒，`artifacts/m3-offline/no-recorder-device.log`），API 35 / x86_64 Debug 包。包含原 38 + 离线核心 7 + 统一列表 1 + 私密入口 1。
- 明确排除 uBO 首装专项 `AdblockIntegrationTest` 以及需分阶段运行的 `DownloadRestartTest` / `OfflineRestartTest`，不将排除项算成通过。
- 此前 46 项回归通过（`before-race-device.log`），新增取消竞态用例后的一轮 47 项却有 9 项失败（`recorder-regression-failed.log`）。该轮并行录屏 / 发布构建，日志先出现 SurfaceComposer 同步超时，之后 AAC flush 超时和多条解码链失败；重启模拟器、不并行录屏 / R8 的最终轮 47 项通过。此对照不能单独证明哪一个负载是根因，录屏兼容仍留作专项，不能删除失败轮或宣称首次全绿。
- 稳定下载页截图 `artifacts/m3-offline/offline-downloads.png` 已检查；`miru-m3-offline.mp4` / `miru-m3-offline-final.mp4` 属于开发过程录屏，不据此认定整个 M3 的录屏 / P0 里程碑门通过。

- Debug / AndroidTest / ARM64 release 与 54 项 JVM 测试通过；最终构建日志 `artifacts/m3-offline/delivery-final-build.log`（2 分 38 秒）。JVM 为原 42 + 共享并发 5 + 离线策略 / MIME 7。
- Debug Lint **0 errors / 17 warnings**，release lintVital 通过，没有新增 baseline。
- APK `app/build/outputs/apk/release/app-release.apk`：versionCode **7** / versionName **0.4.1**，minSdk 26、targetSdk 37，arm64-v8a；**Android Debug** 签名，仅开发安装。
- 大小 **111,202,164 bytes**，仍超过十进制 110 MB，未计为包体验收通过。SHA-256：`2d7b2376bf20e503756062cb84406f58ab70d0420500ec4bf2786461b9f209c1`。
