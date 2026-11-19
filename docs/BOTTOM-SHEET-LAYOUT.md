# 底部面板尺寸与遮罩

浏览器的站点信息、权限、网页提示、外部链接、上下文菜单、视频、下载和广告拦截都使用共用的 Material 3 面板。常规面板最多占可用高度的 90%，广告拦截按 §5.7 最多占 70%。短面板跟随内容高度。正文在柄和系统栏预留之后计算上限，原生拖拽位置仍以窗口底边为准。站点信息直接展开，视频列表给底部的短视频入口留出位置。

面板采用 28 dp 顶部圆角、32 × 4 dp 把手和 `surfaceContainer`。遮罩按主题设置浅色 32%、深色和无痕 50%。

## 验证

- 固定构建 `bottom-sheet-layout-build-03` 通过，778 项源码输入一致，Lint 有 29 项警告、0 项错误。设计、媒体检测和下载模块共 31 项 JVM 测试通过，广告拦截模块的 JVM 任务没有测试源。
- `bottom-sheet-layout-device-04` 中的三项布局专项和真实网页的视频面板用例通过。实测了 90% 和 70% 上限、底边贴合、长内容滚动、短面板和深浅色遮罩。站点信息改为直接展开，测试也等待打开面板后的权限读取完成，再点启用的菜单。
- 站点自动播放专项在前几轮出现菜单未找到和测试网页未就绪超时，原始失败均保留。2026-11-19 在重新启动的专用模拟器上用同一固定包单独运行 `bottom-sheet-layout-device-site-05`，该项通过，实际从站点面板切换三种策略、重载播放并重建 Activity。APK 大小及 SHA-256 已核对，临时 APK 副本和测试用户已移除。证据在 `artifacts/m3-cached-ranges/bottom-sheet-layout-*/` 与 `artifacts/bottom-sheet-layout/`。
- 目前采用固定版 Material 3 的原生开关动效。§7.3 的 300 毫秒进入、200 毫秒退出，以及 §7.2 的减少动画淡入淡出仍待对齐和逐帧验证。[Material 3 1.4.0 底部面板源码](https://github.com/androidx/androidx/blob/5113b82a4ed6416d6344efa50e41db8cb65928ff/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ModalBottomSheet.kt)
