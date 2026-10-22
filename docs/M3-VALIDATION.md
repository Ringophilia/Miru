# M3 首批：清晰度、字幕与音轨

日期：2026-10-23（Asia/Shanghai）。应用版本 0.4.0 / versionCode 6；这是 M3 第一批开发预览，视频离线、缩略图、超分尚未实现。M0 / M1 / M2 历史限制继续保留。

## 实现

`MediaResolutionProbe` 根据真实尺寸标注 480P / 720P / 1080P / 2K / 4K，阈值以画面短边判断，不根据网址或标题猜测。支持 HLS 主清单 RESOLUTION、媒体清单的 TS / fMP4 init、DASH Representation / AdaptationSet 尺寸、MP4 stsd / 视频 track header、H.264 / H.265 SPS。TS 可识别有限长度图片前缀；普通 AES-128 以明确 IV 或媒体序号解密有界分片前缀。

每次读取最多 393,216 字节，整个探测最多其四倍；最多 12 个请求、清单递归 3 层、整次 20 秒与单请求 6 秒超时。每个目标域名 2 / 全局 4 并发，重定向也按目标域调度；Cookie / Authorization 仅传给最初媒体来源，HTTPS 不降级。关闭面板通过 Compose 行作用域取消队列和活动请求，取消不会写失败缓存。

普通结果保留 30 天，失败保留 6 小时；URL 去 query 并与认证上下文一同摘要分区。磁盘只存 SHA-256 键、尺寸与时间。无痕单独内存缓存，标签变化清理；实际播放器的更高测量可更新缓存。本页视频面板显示探测状态、清晰度和尺寸，播放器显示实际片源信息。

播放器「字幕与音轨」读取真实 Media3 轨道，选择音轨、内嵌字幕或关闭字幕；系统文件选择器可加载最大 2 MiB 的本地 SRT / WebVTT / ASS。ASS 仅保留纯文本，支持 UTF-8 和带 BOM 的 UTF-16。字幕内容与 URI 不持久化、不申请永久 URI 权限；仅字号、底边距和背景不透明度保存数值。关字幕后可重新选择已加载文件，解码重建保留选择，释放时清空字幕内容。

打开字幕文件选择器时抑制自动 PiP，返回恢复先前播放状态。读取中切换 / 关闭字幕会取消尚未完成的导入，避免旧读取重新覆盖选择。

## 已运行专项

- `ResolutionProbeTest` 12 项：清晰度阈值、HLS / DASH、MP4 hierarchy、实际 MP4 / DASH init、真实 H.264 / H.265 TS、图片前缀、畸形容器、AES-128、XML 实体拒绝、缓存 TTL / 升级与私密隔离。
- `LocalSubtitlesTest` 6 项：SRT 多行 / 重叠与结束边界、WebVTT 元数据 / 短时间戳、ASS Format / 逗号 / 换行、UTF-16 BOM、非法时间与大小限制。
- `M3ProbeIntegrationTest` 5 项：设备回环 HTTP 的 HLS / DASH / MP4 范围读取、同源头与跨来源重定向隔离、实例重建 / query 轮换缓存、无痕无文件 / 失败缓存，以及域名并发 2 / 取消队列后释放许可。
- `M3MediaPanelIntegrationTest` 1 项：真实 Gecko 请求触发嗅探，打开面板后主动读取清单并显示 1080P / 1920×1080。上述 6 项已通过，首轮日志 `artifacts/m3/probe-device.log`。
- `M3TracksIntegrationTest` 2 项：实际 Activity / Surface 播放 HLS，切换到 Japanese 并检查 Media3 selected 轨道；选择 / 关闭真实 WebVTT cue；字幕面板三个滑块有可访问名称，实调字号 28sp。无痕场景加载 SRT / VTT / ASS，seek 到字幕内外，软件解码重建后保留本地字幕 / 音轨选择。

首轮 tracks 设备测试暴露 Android ICU 比桌面 JVM 更严格的正则语法：ASS override 的右花括号未转义，导致 ASS 路径失败。已将右花括号显式转义；保留首轮失败日志 `artifacts/m3/tracks-device-first.log`，不将它描述为首次全绿。修复后两项设备专项通过（12.506 秒，`tracks-device.log`）。

## 素材与支持边界

双音轨 fixture 位于 `app/src/androidTest/assets/media/m3-tracks/`，脚本 `tools/generate-m3-tracks-fixture.ps1`。素材为合成 320×180 H.264 画面、440 / 880 Hz AAC 音轨与自写 WebVTT / SRT / ASS；H.265 单帧解析 fixture 的生成说明位于 `feature/media-detect/src/test/resources/resolution/README.md`。全部仅用于测试，不进入 release 包。

清晰度尚不支持 HLS byte-range 偏移 / 加密 fMP4 map、跨包 PAT / PMT、超预算 MP4 moov、无尺寸 DASH 和 UTF-16 / UTF-32 MPD，这些返回未知；不阻止网页或播放器继续工作。字幕不支持复杂 ASS 特效、图片字幕、字体附件或无 BOM 的非 UTF-8 编码。完整公网站点、直播轨道变化、真机 SAF 文件提供方、无障碍与性能仍待验收。

本地字幕设备测试通过公开的内存导入 API 驱动，尚未自动点击完整系统 SAF 选择文件流程；选择器期间 PiP 抑制与回来恢复的生命周期保护已有代码，仍需真机验证。截图已检查稳定面板和实际字幕；控制层显示时字幕也会被其渐变压暗，字幕与控制区的避让继续作为 UI 打磨项保留。

## 最终回归与产物

- API 35 隔离模拟器常规设备回归 **38 / 38 通过**（137.221 秒，`artifacts/m3/delivery-device.log`），包含既有 30 项和本轮 8 项；完整 TestRunner / 运行日志保留为 `delivery-logcat.txt`。本轮 ARM64 APK 未在真机安装运行，设备回归使用 x86_64 Debug 包。
- 最终 Debug / AndroidTest / arm64 release 构建、42 项 JVM 单元测试、Debug Lint 与 release lintVital 通过（`artifacts/m3/delivery-build.log`，2 分 9 秒）。JVM 为既有 24 + 清晰度解析 / 缓存 12 + 字幕解析 6，失败 / 错误均为 0。
- Debug Lint **0 errors / 17 warnings**，没有新增 baseline；JS classifier / web-hold 脚本、bridge JS 语法检查与 `git diff --check` 通过。
- 稳定截图 `artifacts/m3/m3-embedded-subtitle.png` / `m3-tracks-panel.png` 已检查，前者显示实际视频字幕，后者显示 Japanese 音轨、选中的英文字幕和样式滑块。

常规回归继续明确排除 `AdblockIntegrationTest`（已知 uBO 首装偶发超时）和需要手动分阶段运行的 `DownloadRestartTest`。M1 的 500 MB 进程重启验证不重复计入本轮。

APK：`app/build/outputs/apk/release/app-release.apk`，versionCode **6** / versionName **0.4.0**，minSdk 26、targetSdk 37，release 仅包含 arm64-v8a。

大小 **111,123,172 bytes**（约 106.0 MiB，仍超过十进制 110 MB，不计为体积验收通过）。SHA-256：`8a7a9602921537bafa44a0b5aff831f8f426773a15cddb0a9193747f41468b1e`。签名确认为 **Android Debug**，供本地开发安装，未公开发布；arm64 真机待验收。
