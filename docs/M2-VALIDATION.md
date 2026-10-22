# M2 验证记录

日期：2026-10-22 至 2026-10-23（Asia/Shanghai）。版本：0.3.0，versionCode 4。仍是开发预览；M0 / M1 未完成项不会因进入 M2 自动视为通过。

## 实现边界

媒体识别运行在自有 `miru-bridge` 中：background webRequest 收集最终响应 MIME、总长度和允许的请求头，content script 在所有 frame 中读取媒体元数据。消息经 session 专属 `miru_media` 原生端口关联 AC 标签，不根据网页传来的 tab ID 授权。每个 frame 只保留当前端口的快照，同资源优先采用正在播放 / 已有时长的元数据。导航、关闭标签时清除索引，单标签最多 64 项。

HLS / DASH 清单优先于视频类型；`.ts` / `.m4s` / `.aac` 分片不单独上报，已知小于 200 KiB 的视频预览也不会从 DOM 扫描重新进入列表。音频响应仅在大于 1 MiB 时识别。URL 按 origin + path 去重，保留最新完整地址。过滤判定为纯 JS，供后续 iOS 复用。DRM 与未找到原始地址的 MSE 资源保持网页播放。

`PlayerActivity` 独立运行，使用 Media3 1.11.1。播放上下文、Cookie、Authorization 与回退回调只在进程内的一次性 token 表中传递，不写进 Intent / SavedState / 播放数据库。Token 60 秒过期、最多 8 个、消费后删除；进程丢失时安全关闭。普通播放使用缓存和 Room 进度，无痕不读写两者，Activity 有 FLAG_SECURE，媒体会话使用通用标题。

HTTP 清单和分片采用同一个请求头来源；跨 origin 的请求 / 重定向移除 Cookie 和 Authorization。缓存键加请求头摘要前缀，隔离不同认证上下文。HLS / DASH 清单即使尝试直链回退也绕过缓存，分片与 Progressive 源走 SimpleCache。缓存默认 1 GB、缓冲默认 60 秒。

播放器设置保存在 DataStore：缓存上限、预缓冲、双击 / 长按 / 横滑参数、中心双击、亮度 / 音量手势、触感与自动 PiP。应用作用域只有一个缓存预算观察者；调整预算时在 SimpleCache 的锁内重建 Media3 LRU 索引，按旧到新的触碰时间注册，不关闭缓存或正在播放的播放器。占用查询不会把用户选择重置为默认值。清除缓存只删除流缓存资源，播放进度单独确认清除；活动播放可能重新缓存或记录，界面明确说明。缓冲设置下次打开源生效；计费网络且系统 Data Saver 开启时上限 30 秒。

## 已实际运行

在隔离的 API 35 `WfmAdApi35`、`emulator-5570` 上使用本地 HTTP origin 与合成 H.264 / AAC 视频。设备为 x86_64 debug；arm64 release 的真机运行另行验收。

包含原有浏览 / 书签 / 下载 / Cookie 和首批四项媒体用例的 **21 项整套回归，20 通过、1 失败**（352 秒）。唯一失败仍为 uBO 签名扩展首装 240 秒超时；未修改签名或 blocklist 策略绕过它。日志 `artifacts/m2/full-device.log`，逐项记录 `full-device-logcat.txt`。该轮早于最后的播放器视觉修正和第五个跨域用例，不能称为最终源码的全套全绿。

首批四项 `MediaIntegrationTest` 整组通过（约 45 秒），日志 `artifacts/m2/media-four-tests.log`：

1. 真实 Gecko 页面识别 VIDEO、带 MIME 的 DASH 和 HLS，共 3 项；不把分片当视频，读取实际 Cookie、Referer 及 HLS Authorization。通过真实地址栏胶囊和视频面板进入播放器，网页约 8 秒的位置衔接误差小于 2 秒，解码得到 320×180。实际注入触摸事件，验证长按到 2x、松手回 1x、右侧双击 +10 秒，随后进入 PiP 并保持播放。
2. HLS 正常重播：两次都请求清单，第二次不再请求已缓存分片。无痕播放再次请求全部分片，实际分片请求携带认证头。
3. 无痕 iframe 中的视频关联到正确私有标签，prepare 命令到达该 frame 并取得约 8 秒位置，关闭标签后媒体索引删除。
4. 真正 Room 提交后重新创建播放器，从约 16 秒继续；无痕从头开始，并且其 25 秒进度不覆盖普通记录。

独立 JS 测试 `node tools/media-classifier-test.mjs` 通过，覆盖 MIME、签名 query 去重、音频大小、预览 / 分片 / 广告域过滤和请求头白名单。新增 3 项 `PlaybackPolicyTest` 通过，覆盖安全缓存归一化、保留选择内容的 query、续播边界和页面隔离。原有 18 项 JVM 测试同轮通过。

后续跨 origin 凭据测试、短预览 DOM fixture、主题 / 细进度条与反馈遮挡修正，以及三项 Cookie 回归共 **8 项通过**，日志 `artifacts/m2/final-targeted-device.log`。初次补充回归有长按时序失败（`final-media-cookie-device.log`）；测试随后明确等待 seek 后恢复播放、缓冲结束及横屏布局，再注入触摸，并保证失败时也发送抬起事件，复跑通过。

继续增加设置后，**6 项媒体设备测试通过**（约 95 秒，`artifacts/m2/settings-media-device.log`），新增验证了 512 KiB 缓存预算的即时淘汰、512 MB 设置的持久化 / 重开、设置传入活动播放器、清除缓存保留真实 MediaStore 下载。随后进一步补充清除 Room 进度和非默认 3x / 5 秒触摸参数断言，最终结果在收尾时记录。

设置扩展后的 **6 项媒体 + 3 项 Cookie 共 9 项通过**（约 152 秒，`artifacts/m2/settings-final-device.log`），包括真实触摸的 3x 临时倍速 / 恢复、5 秒双击跳转与清除 Room 进度。JVM 总计 **22 项通过**（原有 18 项 + 播放策略 / 偏好 4 项）。最后将 Data Saver 的后台豁免应用也纳入省流判断，并给设置开关补充朗读名称，最终产物另作交付回归。

**最终交付源码的 6 项媒体 / 设置用例全部通过**（约 30 秒，`artifacts/m2/delivery-media-device.log`）。该轮明确在缩小 LRU 预算之后重新填满缓存，再从界面清除，避免以已经为空的缓存误判通过；同时验证进度清除与非默认触摸参数。Cookie 的 3 项回归以上一轮 9 项结果为准，其桥接源码之后未再改变。此结果不能抵消前述全套回归的 uBO 首装失败。

截图检查后补充浏览器窗口重新获得焦点时的系统栏对比度恢复，避免从深色播放器 / 对话框返回后浅色页面沿用白色图标。**该最终源码在新启动的隔离模拟器上常规回归 22 / 22 通过**（约 79 秒，`artifacts/m2/final-regression-device.log` / `final-regression-logcat.txt`），覆盖浏览 5、库 4、Cookie 3、下载传输 3、下载 UI 1、媒体 / 设置 6；设置用例还断言浅色页面系统栏标志已恢复。

这轮明确通过 `notClass` 排除 `AdblockIntegrationTest`（本轮前次整套已复现 240 秒首装失败）和 `DownloadRestartTest`（需要外部进程间操作）。因此 **22 / 22 不等于完整 M1 / M2 或全套含首装验收通过**，也不覆盖重启手机与公网矩阵。

## 本地产物与构建

最终 Debug / arm64 release 构建通过，Debug Lint 为 **0 errors / 17 warnings**（无新增 baseline），release lintVital 通过；日志 `artifacts/m2/final-focus-build.log`，最后测试 APK 构建见 `final-regression-test-build.log`。JavaScript 语法 / classifier 检查与 `git diff --check` 通过。

`app/build/outputs/apk/release/app-release.apk`：`app.miru.browser`，versionCode **4** / versionName **0.3.0**，minSdk 26 / targetSdk 37，arm64-v8a。大小 **111,056,315 bytes**（约 105.9 MiB，十进制超过 110 MB，体积验收仍未通过）。SHA-256：`adf6bc7cfa4885af6a8e853c34157bb9c78c536eb45d41b06c2ca48ecf11a65a`。

apksigner 核实为 **Android Debug** 证书，仅供本地开发安装；未上传、发布或推送。arm64 真机运行仍待验收。最后一轮的触摸截图和设置截图位于 `artifacts/m2/player.png` / `settings.png`，均为真实模拟器界面。

## Fixture 来源和复现

`app/src/androidTest/assets/media/` 为本项目生成的 44 秒合成测试图与正弦音，不含第三方影视片段。测试 origin 只监听设备回环地址，不依赖公网。素材生成命令（FFmpeg 只作开发工具，不随应用分发）：

```powershell
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=24 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 44 -c:v libx264 -preset ultrafast -crf 30 -g 48 -pix_fmt yuv420p -c:a aac -b:a 48k -movflags +faststart -threads 2 fixture.mp4
ffmpeg -i fixture.mp4 -c copy -hls_time 4 -hls_list_size 0 -hls_segment_filename segment%02d.ts playlist.m3u8
```

调试 APK 与 androidTest APK 安装后可独立运行：

```powershell
adb shell am instrument -w -e class app.miru.browser.MediaIntegrationTest app.miru.browser.test/androidx.test.runner.AndroidJUnitRunner
```

常规全套排除需要进程间手动操作的 `DownloadRestartTest`；uBO 首装仍可能访问 Gecko 远程设置，不属于完全离线测试。

## 尚未验收 / 实现

- DASH 目前只验证响应识别，尚未验证真实 DASH 视频起播；外部 content URI、本地文件、直播和 FLV 也需专项。
- 401 / 403 登录态轮换、网络切换 / 断网恢复、解析回退、软解回退的完整播放器设备测试；代码路径已接入，不能用源码检查代替实测。
- 多视频 MSE 关联、DRM、多域 CDN 请求头策略、Service Worker、公网网站矩阵，耳机 / 蓝牙 / 来电焦点及 PiP 真机生命周期。
- 横滑、亮度 / 音量与滑块完整触摸专项；目前已实测的是长按与双击。真实系统 Data Saver 降档、完整竖屏布局、无障碍等价操作与动效打磨仍待补齐。
- 自动接管、网页内长按倍速、清晰度主动探测、字幕 / 音轨、视频离线、缩略图和超分未实现。
- M1 签名 uBO 首装偶发 240 秒超时仍保留在历史报告，不能由媒体测试的成功抵消。

## 上游依据

- [Media3 版本记录](https://developer.android.com/jetpack/androidx/releases/media3)（本轮固定 1.11.1）。
- [Media3 数据源与缓存](https://developer.android.com/media/media3/exoplayer/network-stacks)。
- [GeckoView 内容与 Native Messaging](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html)，同时核对本地固定 AC 158.0.2 source JAR。
- [Android Data Saver 状态](https://developer.android.com/develop/connectivity/network-ops/data-saver)：开启与后台豁免状态都应节省计费流量。
- 手势参数按 Miru 规范与本地 Ringo-Cove 行为核对；Kotlin / Compose 实现为本项目新编写，未复制 Ringo-Cove 源文件。
