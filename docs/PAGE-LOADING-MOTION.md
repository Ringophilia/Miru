# 网页加载进度条

实现 §5.3 和 §7.3 的地址栏加载进度条。顶栏和底栏配置都在工具栏底边绘制 2 dp 线条，显示后不改变工具栏或网页的布局尺寸。加载开始时立即显示 10%，随后用现有弹簧参数追赶浏览器进度。新进度会从当前显示值接续，收到新页面的起始读数时重新开始。[Gecko 页面进度回调](https://searchfox.org/mozilla-central/source/mobile/android/android-components/components/browser/engine-gecko/src/main/java/mozilla/components/browser/engine/gecko/GeckoEngineSession.kt)

加载结束先到满格，从满格开始停留 150 毫秒，再用 200 毫秒淡出。开始下一次加载、切换标签或打开地址编辑都会结束上一页的残留动效。关闭系统动画时，加载中直接显示最新读数，结束后保留淡出。读屏语义包含网页加载进度的名称和范围。

## 验证

- 固定构建 `page-loading-motion-build-02` 编译通过，748 项源码输入无漂移，Lint 0 错误、29 警告。设计模块 3 项 JVM 通过，工具栏模块的 JVM 任务无独立用例。
- API 35 x86_64 模拟器 `page-loading-motion-device-02` 通过 4 项。Compose 帧时钟和实际画面采样验证了起始读数、弹簧追赶和中途接续、满格停留、淡出、快速连续加载、切换标签、编辑地址及减少动画。另用真正的 Gecko 网页先阻塞本地 HTTP 响应，再核对顶栏和底栏的绘制位置、像素厚度、结束后清除和工具栏高度。
- 首轮有一项把 Material 组件的扩大无障碍范围当成绘制厚度，原结果保留。修正测试后主包二进制一致，最终重跑全部通过。安装包大小和 SHA-256 已核对，临时 APK 副本及测试用户已移除。证据在 `artifacts/m3-cached-ranges/page-loading-motion-*/` 和 `artifacts/page-loading-motion/`。真机、高刷新率及实际 TalkBack 朗读仍待验证。
