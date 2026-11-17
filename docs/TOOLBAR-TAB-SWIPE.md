# 工具栏横滑切换标签

实现 B16 和 §7.3 的横滑跟手预览。滑动范围按标签列表顺序限制在当前普通或无痕组。当前预览带着相邻预览跟手平移，位移达到半屏或在同方向达到 1,000 dp/s 时切换。短滑和组边界回弹至原页，弹簧接续松手速度。菜单点击、页内查找和其他正常操作继续由原控件处理。减少动画时改用交叉淡入淡出，运行中切换系统动画设置会取消在途手势。

普通网页的预览复用 Gecko 截图，检查绘制会话与标签归属，缩小后仅留在内存。最多保留 12 张，页面没有截图时显示占位。无痕继续启用系统禁止截屏标志，并使用通用占位。预览动画期间保持现有 GeckoView，提交手势才切换会话。[Gecko 截图接口](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/android-components/components/browser/engine-gecko/src/main/java/mozilla/components/browser/engine/gecko/GeckoEngineView.kt)

## 验证

- 固定构建 `toolbar-tab-swipe-build-02` 通过，755 项源码输入无漂移，Lint 0 错误、29 警告。设计模块 3 项、浏览器引擎 133 项 JVM 通过，引擎 1 项 Windows 符号链接测试跳过，App 没有独立 JVM 用例。
- API 35 x86_64 模拟器上，`toolbar-tab-swipe-device-01` 通过 4 项，补齐底栏和减少动画后 `toolbar-tab-swipe-device-02` 通过 5 项。实际手势在拖动中取得左右两张不同颜色的 Gecko 页面像素，验证短滑回原页、过半切换和短距离高速切换。底栏验证无痕组边界、组内切换、没有无痕截图和 `FLAG_SECURE`。运行中关闭系统动画后，在途手势恢复原页，下一次手势实测交叉淡入淡出。原来的菜单按钮、标签切换器和 Gecko 导航栈也通过。
- 校验了 APK 安装大小及 SHA-256，已移除临时 APK 副本与测试用户，并恢复系统动画设置。证据在 `artifacts/m3-cached-ranges/toolbar-tab-swipe-*/` 和 `artifacts/toolbar-tab-swipe/`。真机、不同屏幕刷新率和单手手感仍待验证。
