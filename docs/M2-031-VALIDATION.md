# M2 补齐记录：0.3.1

日期：2026-10-23（Asia/Shanghai）。仍为开发预览；0.3.0 的历史结果与限制见 [M2-VALIDATION.md](M2-VALIDATION.md)。

## 本轮实现

- 网页视频按住 500 ms 临时倍速，默认启用，速度复用播放器长按设置。松手、移动、取消、暂停、移除、页面隐藏、接管或关闭设置均恢复；站点主动接管手势或改速时让位。提示使用隔离样式，原生设置即时发给当前 / 新内容端口。自有 bridge 升至 0.3.2（应用版本为 0.3.1）。
- Gecko 的原生长按菜单可能先于 JS 定时器执行。仅对符合条件的待识别视频长按抑制该 contextmenu，避免指针被提前取消；不阻止短点击、正常拖动或站点已取消的事件。
- 网页全屏状态直接来自 BrowserStore；隐藏浏览器工具栏与系统栏，返回先退出全屏并保留页面，导航 / 切换标签退出原全屏。全屏采用深色表面，顶部提供短暂的原生播放入口。
- 自动接管默认关闭。开启后只接管确认正在播放、有有限时长与视频尺寸、非 DRM 且有 HTTP(S) 地址的媒体。直播、未知时长、纯音频和不可接管 blob 保留网页；失败源不会自动反复启动播放器。防重复状态仅存应用进程内，跨 Activity 重建保留，导航 / 关标签清除。
- 横视频可从全屏退至竖屏 16:9 画面，下面显示标题 / 来源及操作；竖视频和 PiP 保持整屏。手势与 PiP sourceRect 仅覆盖视频区域，锁定同时锁方向；播放选项增加亮度 / 音量滑块及朗读名称。
- 修复认证刷新期间释放播放器后回调仍可能重新建播放器的问题：刷新 Job 有界等待、可取消，返回时核对源版本、实例和释放状态；异常回退一次。不同播放器使用独立 MediaSession ID。

## 已运行的专项

`AdaptivePlaybackIntegrationTest` 的 5 项已分批通过：

1. 真正的 12 秒 H.264 / AAC DASH，通过 PlayerActivity 的实际 Surface 解码，观察 320×180 与播放位置推进；MPD、音视频初始化及媒体分片均验证请求头。
2. 原请求分别返回 401 / 403，刷新一次后实际起播。
3. 刷新后仍 403，仅回退一次，后续请求数量保持稳定。
4. 慢刷新尚未返回时释放，随后完成回调也不重建播放器或发出请求。
5. 刷新回调抛异常，回退一次且不崩溃。

`WebMediaInteractionTest` 的 2 项已通过（`artifacts/m2-031/web-confirm.log`）：真实 Gecko 可信触摸持续 2.5x 并松手恢复、设置关闭后不改速、网页全屏返回保留页面与工具栏，以及自动接管默认关闭 / 开启后暂停网页并原生播放。

`PlayerLayoutIntegrationTest` 已通过竖屏 16:9、标题 / 来源位置、亮度 / 音量滑块语义、重新全屏和同一播放器实例保留。前述专项合计 8 项在分批中通过。首轮 DASH 以无 Surface 的控制器等待尺寸而超时，随后改为真实 Activity / Surface；网页测试补上 Compose 帧推进。历史失败日志保留为 `new-device.log` / `new-device-final.log`，不能描述成首次就全绿。

纯 JS 的 `web-video-hold-test.mjs` 与 classifier 检查通过；新增自动接管单元测试覆盖暂停、音频、未就绪尺寸、DRM、直播 / 无限时长和 blob 拒绝。

## Fixture 与范围

DASH 测试素材从本项目的合成视频生成，位置 `app/src/androidTest/assets/media/dash/`；脚本 `tools/generate-dash-fixture.ps1`，不含外部影视内容。所有页面与媒体请求均为设备回环 HTTP 或本地测试文件，不依赖公网内容服务。

不重复计入通过：uBO 首装仍有已记录的 240 秒超时；分阶段杀进程测试在 0.2.1 已验证，不属于本轮常规回归。公网网站 / Service Worker、多视频 MSE、真实 DRM、真机系统省流与焦点、超分、视频离线和完整浏览器站点权限仍待完成。

## 最终验证与安装包

- 交付源码常规设备回归 **30 / 30 通过**（约 108 秒）：`artifacts/m2-031/delivery-device.log`，逐项记录 `delivery-logcat.txt`。包含自适应 / 认证 5、浏览 5、库 4、Cookie 3、下载传输 3、下载 UI 1、既有媒体 6、竖屏布局 1、网页交互 2。此前整组 30 项也曾通过（`final-device.log`），最终轮覆盖应用进程内的自动接管防重复状态与导航清理。
- 明确排除 `AdblockIntegrationTest` 和需要分阶段操作的 `DownloadRestartTest`。因此 30 / 30 不是 uBO 首装或所有规范验收全绿；历史首装超时不会被此结果抵消。
- **24 项 JVM 测试通过**：地址 5、引擎队列 3、uBO 元数据 2、下载 8、播放策略 / 偏好 4、自动接管边界 2；两个纯 JS 测试脚本通过。
- Debug / arm64 release 构建通过，Debug Lint **0 errors / 17 warnings**，release lintVital 通过，无新增 baseline；日志 `artifacts/m2-031/delivery-build.log`。`git diff --check` 通过。
- 竖屏真实界面截图：`artifacts/m2-031/portrait.png`，已检查视频矩形、标题 / 来源和操作区布局。

APK：`app/build/outputs/apk/release/app-release.apk`，versionCode **5** / versionName **0.3.1**，minSdk 26、targetSdk 37、arm64-v8a。

大小 **111,076,004 bytes**（约 105.9 MiB；仍超过十进制 110 MB，未计为体积验收通过）。SHA-256：`89ead542cfccad2a18c7a80c14d1a6915adeac65a9f6c504dfa66a1166429fa4`。apksigner 确认为 **Android Debug** 证书，仅开发安装，未公开发布；arm64 真机仍待验收。
