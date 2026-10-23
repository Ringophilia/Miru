# M3 超分与原生视频输出验证

日期：2026-10-23（Asia/Shanghai）。当前源码版本为 **0.5.0 / versionCode 8**；本文记录正在验证的开发批次，不能据此认定 M3、整份规范或正式发布验收完成。离线批次已单独提交为 `fbca123`，本批超分待完成验证后单独提交。最终构建、提交和设备复测结果由执行者在本文末尾补记。

## 来源与真实处理链

来源为产品规范第 8.8 / 13 节指定的本地 `G:/Projects/Ringo-Cove`：

- `packages/video_player_android/android/src/main/kotlin/io/flutter/plugins/videoplayer/superres/HookShader.kt`：移植 mpv hook 解析及尺寸 / 条件的逆波兰表达式求值。
- 同目录 `SuperResRenderer.kt`：保留 OES → RGBA16F → 多 pass FBO → Catmull-Rom 输出链，改用独立 Android SurfaceView、异步初始化和释放、明确的输出所有权与暂停重绘。
- `lib/core/super_res.dart`：链选择、contain / cover / fill、设备上限与保护策略按 Miru 要求重写为纯 Kotlin。Miru 自动模式的 **90%** 阈值不沿用 Cove 的 1.2 倍阈值。
- `android/src/main/assets/anime4k/` 实际共 **14 个 GLSL**，全部原样复制并逐项核对 SHA-256 一致。Anime4K 使用 S / M / L 真实 CNN 网络；第二次放大使用 S / S / M。通用模式运行 AMD FSR 1.0 EASU + RCAS 两 pass，三档偏好对 FSR 使用同一条链。
- Anime4K 的 bloc97 MIT 和 AMD MIT 原文分别位于模块 assets 的 `anime4k/LICENSE` / `FSR_LICENSE`；原插件 BSD-3-Clause 通知保存在模块 `licenses/`，也打包到 assets。来源与修改范围见 `feature/superres/NOTICE.md`。Miru 的署名不替换上游许可。
- 复制 `assets/superres/benchmark.mp4`：真实 H.264、1280×720、24 fps、3 秒、126,011 bytes 的合成视频，SHA-256 为 `3114a95c73951ab5c1b579679285da56036bf89cea5e2979a476a82805cbe5b8`。不使用公网站点视频或外部媒体流作为成绩来源。

## 用户入口与生命周期

播放器顶栏及竖屏详情的「画质✦」打开右侧面板：关闭（默认）/ 自动 / 动画线条 / 通用，三档、实际片源信息、输出尺寸、实时处理时间及原画对比。统计每 300 ms 更新。设置页提供同样的偏好及性能测试入口；仅模式、档位和数值形式的设备上限持久化。

`RoutedVideoView` 只有一个显示 Surface。默认关闭时直接把它交给原 ExoPlayer；开启先断开 codec 输出，再把 renderer 的输入 Surface 交给同一个播放器。关闭或错误时先断 codec，等待异步 EGL 释放完成，再交还显示 Surface。媒体源、缓存、音轨和播放位置不因画质开关重建。Surface / route generation 过滤旧回调，销毁不复用已失效的 Surface，Compose `onRelease` 调用永久 `dispose()`。

原 PlayerView 的视频 Surface 所有权由路由替代；独立 SubtitleView 继续显示完整内嵌 Cue（保留文本定位、bitmap 等 Cue 信息）和本地字幕的样式 / 关闭状态。原手势、屏幕方向、竖屏布局与 PiP 入口继续由播放器处理。

contain 的留黑边、cover 的负偏移裁剪、fill 的填充都使用真实图像矩形。对比两侧分别采样原始解码纹理和处理链结果；Compose 叠加 2 dp 分割线、把手和标签，触摸拖动及可访问进度操作仅更新 split。重绘暂停图像不需要恢复播放。

保护只改变当前有效策略，不覆盖用户选择：自动模式按标题 / URL / 域名判断动画；源达到输出 90% 时跳过自动处理；HDR、受保护内容、速度超过 1.5×和 PiP 使用直接输出。连续 30 个实测帧超过帧预算 85% 才形成过载事件，按配置 generation 防止重复处理；持续负载可持久记录设备上限。热状态 SEVERE 降档、CRITICAL 关闭，热降档不写永久设备上限。真实设备上的动态 HDR / DRM、热状态及 PiP 往返仍需专项验收。

renderer 优先使用 `GL_EXT_disjoint_timer_query`；没有可用 GPU timer 时使用同步渲染耗时，状态明确为 `SYNCHRONIZED_RENDER`，UI 不将它标为 GPU 硬件计时。实时值使用 EMA；性能测试使用 warmup 后的实测算术平均。缺失 / disjoint 测量打断连续过载计数。

性能测试 Dialog 独立播放本地片段，以固定 **1920×1080** SurfaceView 缓冲逐档执行真实 Anime4K。每档使用独立 renderer，要求输入 / 输出尺寸准确、有效 pass、至少 60 个新解码并完成输出的帧、正数实测时间；45 秒超时，热状态过高或生命周期中断会失败。三档都完成后，才允许应用低于 16 ms 的最高档。**目前尚未通过实际 Dialog 全流程及推荐应用的设备专项**，不能仅凭代码或底层链测试宣称这项已验收。

## 已有证据与失败记录

| 检查 | 已有证据 | 当前结论 |
| --- | --- | --- |
| 纯策略 / shader 解析 | `SuperResPolicyTest` 8 项、`HookShaderTest` 2 项；CLI 编译 / JUnit 通过，最新 Gradle `testDebugUnitTest` XML 亦为 **10/10、0 failures / errors**（2026-10-23T13:00:39Z） | 证明链、90%阈值、分类、几何、保护与真实 shader pass 解析；不证明 GL 性能 |
| 首次统一 Debug / AndroidTest 构建 | `artifacts/m3-quality/initial-build.log`，`BUILD SUCCESSFUL in 52s` | 编译通过；当时不是完整验收 |
| 真实 Anime4K 三档 + FSR | `SuperResIntegrationTest.allThreeRealAnimeNetworksAndFsrCompleteSixtyDisplayedFrames`，`artifacts/m3-quality/initial-device.log` | **通过**：同一实际解码器、720P 合成片段，四条链分别至少 60 帧，1920×1080、有真实 pass 和非黑 PixelCopy 输出 |
| shader 加载失败回退 | `actualShaderFailureFallsBackToDirectAndPlaybackContinues`，同上日志 | **通过**：真实缺失 shader 加载错误、仅一次错误通知，异步回到直显，同一 ExoPlayer 仍播放且有实际图像；不扩大为所有驱动 / FBO 运行期故障已验证 |
| 画质开关、同帧对比、旋转 | `qualitySwitchComparisonAndOrientationKeepTheSamePlayer`，首轮及 `fixes-device.log` | **失败记录保留，修复后待复测**；不能计为通过 |
| 暂停切配置后的重绘 | 代码修复及同一测试新增断言 | **待真实设备复测**；不能以 resume 掩盖 |
| 本机性能测试 Dialog / 推荐应用 | 已有真实实现，尚无该 UI 全流程结果 | **未验收** |
| 540P→1080P、骁龙 8 Gen 2 级均衡 <12 ms | 无匹配的物理设备测试记录 | **未验收** |

最初五项联合设备目标包含三个超分测试和两个离线通知测试，共有三个失败，不是全绿。超分其中两项通过；离线通知结果由其专项文档单独记录，不归入超分通过数。

当前单元测试的直接记录位于 `feature/superres/build/test-results/testDebugUnitTest/TEST-app.miru.superres.HookShaderTest.xml` 与 `TEST-app.miru.superres.SuperResPolicyTest.xml`；任务行保留于 `artifacts/m3-quality/commit-validation-build.log`。该 XML 仅证明此模块的 10 项，不作为其他模块或完整设备回归已通过的证据。

对比测试的诊断包中，真实 routing 为 `GENERAL / BALANCED`、`bypass=null`、`active=true`、12 帧、错误为空、未在释放、热状态 0；质量 Dialog 仍存在。因此不是 90% 自动跳过或热 / 30 帧保护关闭。示例同步渲染耗时约 **227 ms/帧**、预算约 41.7 ms；这是模拟器回退计时，不是符合规范的 GPU 性能成绩。

该测试原先未滚动就点击位于可滚动面板下方的 Switch，输入未启用对比且 Dialog 没关闭。测试已改为真实 `performScrollTo` → `assertIsDisplayed` → 点击，并保留分割节点、enabled / active、同一暂停帧 PixelCopy 差异、关闭后继续播放及旋转保持 ExoPlayer 身份的全部断言。新断言还要求暂停时切 Anime S→M、Fit→Cover：实际 pass / presentation 更新、有效像素，播放不恢复、位置不动，解码帧计数仍为 0。最终是否通过以复测日志为准。

审查还发现独立产品漏洞：旧代码在真实配置 / 输出尺寸变化时重置 frames，却不重绘保留的暂停图像，又以 frames>0 定义 active。已改为成功 presentation 决定 active，配置 / 尺寸 / 输出重接时调用真实 `render(false)`。只有新的解码回调才能增加 benchmark 的 frames；重复 attach 同一 Surface / 尺寸不反复重绘。

## 当前验收边界

以上通过项使用 API 35 隔离 x86_64 模拟器，不代表 ARM64 真机或各 GPU 驱动支持矩阵。尚未完成骁龙性能门槛、实际温控、动态 HDR / DRM、GL 开启时旋转 / PiP / 生命周期完整矩阵、TalkBack 全流程、深浅色和大字体截图核对。播放器旧字幕 / 音轨 / 手势的全量回归也必须在最终源码上重跑。

本批尚未记录最终 release / AAB、签名、尺寸、SHA、操作录屏或完整 lint / 回归成绩；不存在可据本文宣称的正式发布构建。此前 M0 / M1 / M2 / M3 文档中的未验收项继续保留。总体目标仍按 `MIRU-SPEC.md` 的完整范围逐项证明，不能以本批两个 GL 通过项替代整份文档验收。

## 最终结果补记（待执行者更新）

2026-10-23：`commit-validation-build.log` 记录 Debug / AndroidTest 构建成功（3m 8s）；本模块 HookShader 2 项、Policy 8 项 JVM 测试全部通过。`artifacts/m3-quality/commit-superres-device.log` 最终 **3/3 通过，98.099 秒**：三档 Anime / FSR 的真实 60 帧、实际 shader 故障直显回退，以及暂停对比 / 切档 / 画面比例 / 旋转同一播放器的全部断言均通过。此前失败保留作为修复依据。

这是所述 API 35 专项的终态结果；上文标为未验收的 benchmark UI、物理 GPU 性能与完整回归仍未完成。版本号与其他待验证改动由后续应用整合批次记录，不把此模块提交当作完整 0.5.0 发布。

## 性能测试对话框后续专项

`SuperResBenchmarkUiIntegrationTest` 新增两项实际设置界面测试，在 `artifacts/browser-core/clipping-fix-device.log` 均通过。该轮总计9项中8通过、1项页面表单触摸失败；这里不将联合轮描述为全部通过。

- 从真实菜单进入设置 / 播放器 / 本机测试，三档均完成真实测量并展示计时类型。API35 软件模拟器测得极速201.7、均衡263.4、高画质457.2 ms/帧，均为 **synchronized render time**；界面正确显示无推荐档位，未提供应用推荐按钮，也未静默修改设置。这些是模拟器回退计时，不是硬件GPU或<12ms验收成绩。
- 有真实帧进度后取消并重开，再把Activity切至后台；界面报告中断，没有伪造成功结果。取消 / 中断 / 关闭后新增GL和ExoPlayer线程均退出，偏好保持不变。生产每档45秒期限未改。

这补齐上述 benchmark UI 的测量、无推荐与生命周期范围；有硬件档位实际低于16ms时的“应用推荐”及持久化仍需对应设备验证。物理性能、温控和驱动矩阵继续保留。
