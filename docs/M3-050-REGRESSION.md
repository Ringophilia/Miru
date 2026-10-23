# 0.5.0 整合回归修正

2026-10-23，在 34f0bab 源码的 API35 x86_64 专用模拟器上，常规回归首次包含 uBO 安装专项，仅排除需要分阶段外部操作的 DownloadRestartTest / OfflineRestartTest。

`artifacts/m3-quality/baseline-050-full-device.log` 终态 **54 项，2 项失败，335.943 秒**。不能沿用旧版本的全绿结论：

- 关闭字幕后，新的 SubtitleView 仍保留空的全屏视图和字幕语义节点。已改为实际存在 Cue 时才挂载字幕视图，完整 Cue 的定位和图片能力保留。
- 媒体接管测试等待 Media3 READY/playing 后立即读取宽高；真实视频尺寸是独立回调，第一次读到 0。测试现先等待实际非零宽高，再保留原有精确 320×180 及接管时间断言，没有将空格式当作通过。

复现日志 `baseline-050-targeted-device.log` 中字幕项再次失败、媒体项通过，分别支持稳定缺陷与时序竞态的判断。

修正后 `artifacts/browser-core/initial-device.log` 的 7 项联合专项中，这两项均通过；同一日志另有 Cookie 清理与下拉刷新两项独立失败，故整批为 **5 通过、2 失败，103.768 秒**。此处的两项通过不扩大为整个播放器或新的浏览核心批次全部通过。完整整合回归需在后续最终源码上重跑。
