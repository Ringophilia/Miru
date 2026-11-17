# 网页工具栏跟手和回弹

按 §7.2 和 §7.3，手指按住时以 Gecko 报告的实际网页滚动位置推动工具栏。手指离开时，从最后一次显示的位置选定端点。露出超过半高则回到完全显示，否则收起。弹簧使用松手前的工具栏速度，已停止移动一段时间或关闭系统动画时分别从零速度起步或直接就位。新触摸可以打断回弹，回到页顶、加载网页或编辑地址仍会显示工具栏。[Gecko 滚动位置接口](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/android-components/components/concept/engine/src/main/java/mozilla/components/concept/engine/EngineView.kt)

松手后的连续 Gecko 滚动样本继续更新网页位置，用于判断是否到达页顶，工具栏保持这次已经选定的端点。弹簧结束且滚动信号留出至少约 120 毫秒间隔后，工具栏重新接收独立的滚动。网页的惯性和下拉刷新仍由 Gecko 与原生刷新组件处理。

## 验证

- 固定构建 `toolbar-release-motion-build-01` 通过，752 项源码输入无漂移，Lint 0 错误、29 警告。设计模块 3 项 JVM 通过；App 模块没有独立 JVM 用例。
- API 35 x86_64 模拟器 `toolbar-release-motion-device-01` 通过 6 项。实际 Gecko 手势在顶栏和底栏各验证了拖动中只隐藏一部分，松手后分别回到显示或隐藏。受控帧时钟验证了惯性位置更新不改选定端点、速度接续、新按下打断弹簧、半高边界和减少动画的直接就位。原有三项顶底栏、页顶刷新及换页后的刷新回归也通过。
- 两个安装包均核对大小和 SHA-256，移除了 APK 临时副本与测试用户。证据在 `artifacts/m3-cached-ranges/toolbar-release-motion-*/` 和 `artifacts/toolbar-release-motion/`。真机、高刷新率和网站嵌套滚动仍待验证。
