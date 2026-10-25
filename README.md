# Miru

Android 原生浏览器，包名 `app.miru.browser`。产品需求以 [MIRU-SPEC.md](MIRU-SPEC.md) 为准。

由 [Ringophilia](https://github.com/Ringophilia) 与 xiaomao 共同开发。源码：[Ringophilia/Miru](https://github.com/Ringophilia/Miru)。开发者署名见 [AUTHORS.md](AUTHORS.md)。

项目自有代码采用 [Mozilla Public License 2.0（MPL-2.0）](LICENSE)。第三方组件保留各自许可证和署名，详见 [第三方说明](third_party/README.md)与[开源检查记录](docs/OPEN-SOURCE-REVIEW.md)。当前依赖链包含受 Google 专有条款约束的 Play services FIDO 组件，不能据此宣称整个构建产物的依赖均为自由开源软件。

图标由维护者以《Ringo EXPO 08》封面为灵感，通过 AI 生成；素材的授权范围与来源见 [design/PROVENANCE.md](design/PROVENANCE.md)。Miru 与椎名林檎、相关唱片公司、Mozilla 或 Google 没有隶属或背书关系。

目前推进至 **M3 与浏览器核心开发批次（0.5.4 权限首批已提交）**：在 GeckoView / Compose 浏览器、uBlock Origin、断点下载与 Media3 播放器基础上，接入清晰度探测、字幕 / 音轨、HLS / DASH 视频离线、Anime4K / FSR 渲染，以及网页长按菜单、清除浏览数据、滚动工具栏和首批站点权限。B13 完整权限/提示矩阵与最终发行仍未完成。

「设置 → 播放器」可调整缓存上限、预缓冲和手势参数，清除流缓存 / 播放进度。清缓存保留下载文件，无痕播放不读写缓存和进度。

0.3.1 补上网页视频长按倍速、可选自动接管、网页全屏返回和播放器竖屏详情；实际 DASH 与认证恢复专项见 [补齐验证记录](docs/M2-031-VALIDATION.md)。

「本页视频」面板会读取有界的清单 / 媒体头部来显示清晰度，关闭面板时取消探测。播放器「字幕与音轨」可以切换内嵌字幕和音轨，或通过系统文件选择器加载 SRT / VTT / ASS（ASS 以纯文本显示），并调整字幕字号、底边距与背景透明度。字幕内容仅保留在当前播放会话的内存中。实现与验证边界见 [M3 首批记录](docs/M3-VALIDATION.md)。

媒体面板的下载按钮可选择 HLS / DASH 清晰度，关联音轨和字幕一起保存。离线视频与普通文件共用下载页，在「视频」分类中管理；离线缓存与普通播放缓存分开，清除播放缓存保留离线视频。无痕离线需明确确认保留内容。实现、断源完整播放和两阶段进程重启验证见 [离线记录](docs/M3-OFFLINE-VALIDATION.md)。

uBO 使用原版站点面板、过滤规则和例外列表。在扩展就绪前加载的页面，以及扩展以 `!` 标记的页面，工具栏会显示刷新警告。首次安装有历史超时记录，固定官方 blocklist 资源后已有受控 fresh-profile 结果，多轮真机与在线更新仍需验证。超分渲染与实际本机测试界面已有受控设备证据，物理 GPU 性能尚未验收；缩略图预览和 P2 离线导出 MP4 尚未实现。完整需求与待验收门见 [全量矩阵](docs/ACCEPTANCE.md)。

## 构建

需要 JDK 21、Android SDK Platform 37.0、Build Tools 37.0.0。依赖固定在 `gradle/libs.versions.toml`；工程使用 Gradle Wrapper，不需要全局安装 Gradle。

1. 用 Android Studio 打开本目录，并将 Gradle JDK 设为 JDK 21。
2. 在 SDK Manager 安装 Android 17 / API 37.0，或运行 `sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`。
3. 在未提交的 `local.properties` 设置 `sdk.dir=你的SDK绝对路径`，或使用 `ANDROID_HOME`。
4. 运行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat testDebugUnitTest :core:common:test :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=app.miru.browser.DownloadRestartTest,app.miru.browser.OfflineRestartTest,app.miru.browser.RuntimeSitePermissionIntegrationTest"
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。Android 8.0 及以上；调试版包含 `arm64-v8a` 与模拟器 `x86_64`，当前 release 配置仅包含 `arm64-v8a`。发布签名从未提交的 `keystore.properties` 读取；未配置时使用调试密钥，仅供开发安装。

设备测试需要连接模拟器或手机，测试网页由测试进程内的 loopback HTTP 服务提供；全新 uBO 安装仍可能访问 Gecko 远程设置。独立手动 fixture 可用 `node tools/m1-fixture-server.mjs` 启动。`DownloadRestartTest` 与 `OfflineRestartTest` 都必须分阶段运行并在中间强制停止进程，方法见 [M1 验证记录](docs/M1-VALIDATION.md)和[离线记录](docs/M3-OFFLINE-VALIDATION.md)；上述常规命令排除这两类，不把排除算作通过。

`RuntimeSitePermissionIntegrationTest`单独在四项系统权限尚未授权的API35实例顺序运行，实际点击Android权限弹窗；普通回归将其排除，不把排除记通过，见[运行方法](docs/B13-RUNTIME-PERMISSIONS-TEST.md)。站点权限用例的Android预授权与系统弹窗用例具有不同验证范围，不能相互替代。

最新已验证打包的开发包为 [Miru-0.5.4-arm64.apk](artifacts/releases/Miru-0.5.4-arm64.apk)，对应源码 `97d8be1`，**107,966,640字节**，通过严格十进制110MB体积门。SHA-256：`215e07625fa086328433f3d0cebc6391179706ac3c4741a3755a0e8ed52c753a`。release构建成功；最终包压缩字节复用、SDK对齐及同证书重签验证通过，1517项载荷逐项保留，17个原生库均为ARM64。Gecko为4078项原payload保持、1项精确定位策略修改、5项官方blocklist新增，完整检查见[打包工具](tools/apk-release-buildlogic/README.md)与[B13记录](docs/B13-PERMISSIONS-VALIDATION.md)。当前仍为本机Android Debug签名，没有ARM64运行或正式发行验收；旧版APK继续保留。

0.5.4权限首批修复了旁听观察者提前拒绝：最终源码的站点/混合下载专项7/7、全新模拟器真实系统权限专项3/3通过，覆盖摄像头/麦克风live轨道、网站与系统许可分离、临时定位同源新文档重新询问及近似定位。完整生命周期、全部B13功能、全套回归、正式签名与真机门继续保留，详见[专项记录](docs/B13-PERMISSIONS-VALIDATION.md)。

0.5.2全新资料常规回归78项77通过、1项首次安装页面刷新警告失败；渲染专项均通过。0.5.3已修复实际Gecko复现的图片meta策略泄漏、扩展监听就绪时序及广告拦截重复启用导致的重载，107项JVM、46项JS协议、14项设备专项、重复启用和全新安装专项通过；release/lint成功，lint为0错误、22警告。完整回归首次中断后，新实例复跑79项为77通过、2失败（混合下载撤销等待、QUALITY benchmark有效帧超时），不能称整套通过。其107,897,008字节APK及全部失败记录保留；这套历史结果不代表0.5.4完整回归。

## 工程结构

```text
app                 Application / AppContainer / Activity / 导航
core/common         地址判断、搜索引擎定义及 JVM 测试
core/data           DataStore 设置、本地历史记录与书签（SQLite）
core/design         主题、设计与动效 token、矢量字标
browser/engine      GeckoRuntime / BrowserStore / SessionStorage
browser/toolbar     Compose 地址栏
feature/ntp         原生新标签页与快捷方式
feature/tabs        普通 / 无痕标签切换器
feature/menu        主菜单
feature/history     历史记录（日期分组、搜索、删除 / 清除可撤销）
feature/bookmarks   书签（星标、列表、编辑）
feature/adblock     uBO 状态、站点面板、全局开关与设置入口
feature/downloads   Room 分段断点、OkHttp 下载、通知、MediaStore / SAF、下载管理
feature/media-detect 媒体模型、按标签隔离的内存索引、清晰度探测、本页视频面板
feature/player      Media3 播放器、手势、缓存、进度、字幕 / 音轨、媒体会话与 PiP
feature/offline     HLS / DASH 下载、独立离线缓存、清晰度选择、前台服务与持久索引
feature/settings    基本设置与关于
design              跨平台 token JSON 与可编辑 SVG 图标
```

浏览器标签与导航状态只来自 Android Components 的 `BrowserStore`。Compose 读取不可变快照，不维护第二套标签模型。原生新标签页在 Gecko 准备好前即可展示，Gecko 本身按其 API 要求在主线程初始化，会话 I/O 在后台执行。普通标签用原生会话序列化保存导航栈，无痕标签不写入会话文件。

当前借鉴了 reference-browser 的核心组件装配方式，未带入其账号、同步、遥测应用层。上游固定版本、来源、许可证与工具链验证见 [docs/UPSTREAM.md](docs/UPSTREAM.md)。运行时验收见 [M0 记录](docs/M0-VALIDATION.md)和 [M1 记录](docs/M1-VALIDATION.md)，uBO 接口与限制见 [M1-ADBLOCK.md](docs/M1-ADBLOCK.md)。

## 后续

- M0 剩余验收与细节见 [docs/PROGRESS.md](docs/PROGRESS.md)。模拟器结果不能代替规范中的真机性能、兼容性和无障碍验收。
- M1 已通过 500 MB 进程终止后自动续传及本地默认规则的冷启动恢复测试；首装稳定性、扩展自动更新、额外 Cookie 提示规则与完整真机验收仍待补齐，详见进度文档。
- M2：核心播放链路已接入；具体结果与未完成项见 [M2 验证记录](docs/M2-VALIDATION.md)。
- M3：清晰度探测、字幕 / 音轨、视频离线与 Ringo-Cove 超分渲染已接入；缩略图、完整硬件 / 站点 / 生命周期矩阵继续推进，见 [首批记录](docs/M3-VALIDATION.md)、[离线记录](docs/M3-OFFLINE-VALIDATION.md)与[超分记录](docs/M3-SUPERRES-VALIDATION.md)。
- M4：动效、网站矩阵、真机性能与完整验收。
