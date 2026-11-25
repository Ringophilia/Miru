# Miru Chromium 路线迁移计划（v2.0）

> 决策日期：2026-11-25（由维护者拍板确认）
> 配套文档：[MIRU-SPEC.md](../MIRU-SPEC.md) · [AGENTS.md](../AGENTS.md) · [ACCEPTANCE.md](ACCEPTANCE.md) · [STATUS.md](STATUS.md)

---

## 1. 决策背景与路线选择

在完成 GeckoView 基线第四组打磨检查点（`0.5.24`）后，综合评估移动网页兼容、高刷新率滑动流畅度、功耗、冷启动广告拦截竞态、视频嗅探能力与长期产品体验，维护者决定自 **2026-11-25** 起将 Miru 的引擎路线切换为：

- **网页内核**：**去 Google 化自编 Chromium（Cromite 思路）**，移除 Google 服务、遥测、云同步与账号上报。
- **统一 Rust 原生核心（`native/` → 单一 `libmiru_core`）**：在不牺牲原生交互体验的前提下，将所有规则匹配、字节流/清单解析、URL/隐私策略、高吞吐网络与本地检索核心下沉至统一 Rust Cargo Workspace，编译为单一静态库/动态库（共享一份分配器与标准库，避免多份 `.so` 重复膨胀体积），同时供 Chromium C++（零拷贝 `extern "C"`）、Android Kotlin（JNI）与未来 iOS Swift 复用：
  - `miru-adblock`：原生 `adblock-rust` 规则引擎直接挂载在 Chromium C++ `URLLoaderThrottle` / `NavigationThrottle`，配套渲染层外观隐藏、脚本注入与原生元素拾取。
  - `miru-media`：媒体 URL / MIME / VAST 分类与去重 + HLS/DASH 清单与 MPEG-TS / H.264-H.265 SPS / MP4 `stsd` 流头清晰度解析，C++ 网络层一次调用即可完成抓流过滤，零 JNI 往返与零 JVM GC 抖动。
  - `miru-url`：基于公共后缀列表（PSL）的域名解析、`siteOrigin`、Referrer 策略、缓存键签名参数剥离与请求头白名单净化。
  - `miru-storage` / `miru-download`（Phase C3 按实测推进）：SQLite FTS5 地址栏/历史毫秒级检索打分，以及 `tokio` + `rustls` 多分段零拷贝下载核心（保留 Android 原生 `DownloadService` 通知与 SAF/MediaStore `ParcelFileDescriptor` 写入）。
- **原生平台层严格保留（绝不牺牲交互手感）**：**完整保留并复用** Miru 已经建成并打磨好的 Jetpack Compose 浏览器外壳与弹簧动效、Media3 + `MediaCodec` 硬件解码、OpenGL ES 3.0 GLSL 超分（视频帧全程停留在 GPU 纹理，不经 FFI 拷贝），以及 Android 系统服务/权限/画中画/无障碍集成。

### 为什么选这条路、并明确接受哪些代价

| 维度 | 收益 | 明确接受的代价 / 边界 |
|---|---|---|
| 网页兼容与渲染性能 | Blink / V8 移动端网页兼容度最高，90/120 Hz 滑动跟手度、复杂前端站点与移动端功耗表现显著优于 GeckoView | 放弃内置 uBlock Origin WebExtension 扩展；极端反广告拦截站点的个别规则效果可能弱于桌面版 uBO |
| 冷启动与广告拦截 | C++ 网络层同步匹配 `adblock-rust`，**彻底消除扩展冷启动等待与首次安装超时问题**，恢复标签首屏零漏拦 | 需要维护默认过滤列表打包与后台更新机制，并自研原生盾牌面板与元素拾取 UI |
| 视频嗅探与接管 | C++ 网络层比扩展 `webRequest` 更靠底层，抓流与请求头读取更稳定，不再依赖内置 WebExtension 启动时序 | 仍受限于站点 DRM（EME）与强绑定鉴权；无法接管时继续优雅回退网页播放 |
| 安装包体积 | ARM64 单 ABI 发布包目标控制在 **`< 150 MB`**（仍远小于此前双 ABI 未压缩调试包），通过裁剪语言包、非必要 Chromium 组件与 R8 持续压缩 | Chromium 自身 `libchrome.so` + ICU + V8 体积大于 GeckoView，因此单 ABI 体积预算从 v1.0 的 `110 MB` 调整为 `150 MB` |
| 工程与构建成本 | 通过「Linux 构建机 / CI 产出版本化预构建 `.aar` + Windows 日常 Gradle 消费」隔离 C++ 编译成本，上层 Kotlin/Compose 迭代保持秒级/分钟级增量构建 | 需要准备一台 Linux x86_64 构建机或 CI（≥ 64 GB 内存、≥ 250 GB NVMe 固态硬盘），并建立跟随 Chromium 安全更新的定期 rebase 流程 |

---

## 2. 现有代码资产与冻结边界

- **GeckoView 主线冻结于 `0.5.24`**：保留为 Phase C0 期间的可运行参考基线，不再向 GeckoView / Mozilla Android Components / uBlock Origin 方向追加任何新功能（仅保持解耦期间可编译、不引入回归崩溃）。
- **历史证据边界不变**：`docs/ACCEPTANCE.md` 中 `E0`–`E43` 全部属于已冻结的 v1.0 GeckoView `0.5.24` 基线，**不得用于宣称未来 Chromium 引擎已通过验收**；凡依赖网页引擎、广告拦截、视频嗅探、下载 Cookie 桥接与站点权限的验收门，切换至 Chromium 后必须重新验证。
- **既有冻结项继续有效**：P11 磁盘缓存区段（已存 `wip/p11-cache-ranges`）与 P2 项（B22–B24、P23）继续冻结。

### 模块资产盘点

| 模块 | 迁移定位 | 处理方式 |
|---|---|---|
| `:core:common` | **直接复用** | 通用工具、日志、纯业务逻辑保持不变 |
| `:core:design` | **直接复用** | Material 3 色板、六主题色、动效曲线、通用 Compose 组件与无障碍弹窗完全保留 |
| `:core:data` | **直接复用** | 本地 SQLite `BrowsingLibrary`（历史与书签文件夹）、Room 下载/播放库、DataStore 设置已完全去除了 Mozilla Places/Glean 依赖，直接承接 Chromium 路线 |
| `:browser:toolbar` | **直接复用** | Compose 地址栏、在线搜索建议、内联域名补全、网站图标展示与动效保留；仅把依赖从 AC 类型切换为 `:browser:engine-api` |
| `:feature:ntp` | **直接复用** | Compose 原生新标签页、快捷方式、无痕说明页保留 |
| `:feature:tabs` | **直接复用** | 两列网格切换器、滑动关闭、撤销、无痕分组保留；状态源改为 `:browser:engine-api` |
| `:feature:menu` | **直接复用** | 主菜单与退出清理入口保留 |
| `:feature:history` | **直接复用** | 依赖 `:core:data`，直接复用 |
| `:feature:bookmarks` | **直接复用** | 依赖 `:core:data`，含书签文件夹，直接复用 |
| `:feature:downloads` | **直接复用** | OkHttp 多线程分段下载、Room 断点续传、分类/搜索/存储条与 UI 全保留；仅将下载入口和 Cookie 获取桥接改为 `:browser:engine-api` |
| `:feature:offline` | **直接复用** | Media3 HLS/DASH 离线与选清晰度全保留 |
| `:feature:player` | **直接复用** | Media3 播放器、全部手势、侧边抽屉、边播边存缓存、断点续播、PiP、后台播放、跳过片头片尾、字幕音轨、双指缩放全保留 |
| `:feature:superres` | **直接复用** | OpenGL ES 3.0 渲染链、Anime4K v4 / AMD FSR 1.0 着色器、性能测试与自我保护全保留 |
| `:feature:settings` | **直接复用** | 设置页 UI、外观/播放器/下载/隐私设置与站点总览 UI 全保留；广告拦截子项从“打开 uBO 控制面板”改为原生规则/例外管理 |
| `:browser:engine` | **拆分替换** | 拆为纯 Kotlin 契约 `:browser:engine-api` + 冻结后端 `:browser:engine-gecko` + 新建后端 `:browser:engine-chromium` |
| `:feature:adblock` | **解耦重写** | 剥离 uBO `popup-fenix.html` 与 WebExtension 依赖，改为对接 `:browser:engine-api` 的原生 `adblock-rust` 控制器（计数、本站开关、过滤列表、自定义规则、原生元素拾取） |
| `:feature:media-detect` | **解耦适配** | 保留清晰度探测、列表排序与 UI 面板；将输入通道从 `miru-bridge` WebExtension 改为 `:browser:engine-api` 抽象的媒体事件与页面控制接口（Chromium 侧由 C++ `URLLoaderThrottle` + 轻量脚本驱动） |
| `:app` | **装配迁移** | 逐步将 `AppContainer` 与 `MainActivity` 从 AC `BrowserStore`/Gecko 装配迁移至引擎接口，并在过渡期支持通过构建配置切换 `gecko`（参考基线）与 `chromium` |

---

## 3. 核心技术方案

### 3.1 去 Google 化 Chromium 与预构建 `.aar` 流水线

1. **上游基线**：固定 Chromium Android Stable 里程碑版本，并引入 Cromite 去 Google 化补丁集思路，编译期与运行期关闭：
   - Google Play Services（GMS）依赖、账号登录、Chrome Sync
   - UMA / Metrics 遥测、Crashpad 自动上传、RLZ、FieldTrials / Variations 远端实验
   - Google SafeBrowsing 远端上报、Google 建议与预连接服务
2. **Miru 补丁面最小化原则**：
   - 尽量不魔改 Blink / V8 内部渲染逻辑，将 Miru 定制集中在三个边界清晰的 C++/Java 接入点：
     1. Android 嵌入外壳桥接（`WebContents`、标签会话、`OTRProfile` 无痕、`BrowserControls` 动态工具栏、下拉刷新、站点权限/提示/下载/Cookie 委托）；
     2. 网络与导航拦截钩子（`URLLoaderThrottle` / `NavigationThrottle` 调用 `adblock-rust`）；
     3. 媒体嗅探观察钩子（网络层只读观察请求/响应头与 URL，上报给原生层，绝不把 Cookie/Authorization 写入磁盘）。
3. **构建环境分工（解耦 Windows 日常开发与 Linux 内核编译）**：
   - **Linux 构建机 / CI 前提（Phase C1 起）**：Ubuntu 22.04 或 24.04 x86_64、**内存 ≥ 64 GB（推荐 128 GB）**、**NVMe 固态硬盘 ≥ 250 GB**，安装 `depot_tools`、Clang/NDK 与 Rust Android 交叉编译工具链。
   - **制品契约**：Linux 构建流水线产出版本化、带 SHA-256 校验与补丁清单的预构建 Chromium `.aar`（`arm64-v8a` 必选，`x86_64` 可选供模拟器验证），存放至版本化构件源或本机 Maven 缓存。
   - **Windows 本地开发**：Gradle 直接消费预构建 `.aar`，日常修改 Compose UI、下载、播放器、超分与引擎适配层完全无需在 Windows 编译 Chromium C++。

### 3.2 原生 `adblock-rust` 广告拦截

- **核心库**：引入 Brave 维护的 `adblock-rust`（MPL-2.0），支持标准 ABP / uBlock Origin 网络过滤语法、`$important`、`$redirect`、Cosmetic 元素隐藏与 Scriptlet 规则。
- **网络层同步拦截**：将 `adblock-rust` 编译为 Rust 静态库（`aarch64-linux-android` / `x86_64-linux-android`）链接进 Chromium 原生库，在 `URLLoaderThrottle` / `NavigationThrottle` 中直接判定是否拦截或重定向，**启动即就绪、零冷启动竞态**。
- **渲染层外观隐藏与脚本注入**：页面加载提交（commit）时按当前域名查询命中的 CSS 隐藏选择器与 Scriptlet，注入到目标框架；同时保留无痕模式一致生效。
- **默认规则列表**：随包内置一份首启即用的默认规则快照，后台按周期静默更新（EasyList、EasyPrivacy、uBO 内置列表、Peter Lowe、按系统语言启用中文区域列表如 AdGuard Chinese / EasyList China，以及 Cookie 提示等 Annoyances 列表）。
- **原生 UI 与元素拾取**：彻底替换 uBO 网页弹窗，使用 Miru 原生 Material 3 盾牌面板控制本站开关、查看本页/累计拦截数、管理过滤列表与例外站点；元素拾取由轻量 DOM 高亮脚本回传 CSS 选择器，写入用户自定义规则列表并立即生效。

### 3.3 C++ 网络层视频嗅探 + 轻量页面脚本

- **网络层捕获**：Chromium C++ `URLLoaderThrottle` 观察所有框架、重定向与 Service Worker 发起的响应头与 URL，识别 HLS（`.m3u8`）、DASH（`.mpd`）、直链视频（`video/*`）及大于 1 MB 的音频，过滤短视频预览（`< 200 KB`）、独立分片（`.ts`/`.m4s`/`.aac`）与 VAST/VMAP 广告流。
- **请求头内存直传**：针对命中的媒体 URL，从网络请求与 `CookieManager`（按普通 / 无痕 Profile 严格隔离）读取当前 `Referer` / `Origin` / `User-Agent` / `Cookie` / `Authorization`，**仅保存在进程内存中**，传给 `:feature:player` 的 `OkHttpDataSource`；遇到 401/403 时向 Chromium 重新读取最新 Cookie 重试一次。
- **轻量页面脚本**：仅负责三件事——监听 `<video>` 开始播放与进入全屏、响应原生“暂停网页视频并读取当前播放时间”、以及网页内长按倍速（尊重页面自身 `preventDefault`）。
- **iOS 复用保持不变**：媒体 URL / Content-Type / 去重 / 短视频 / VAST 判定规则继续维护为独立可共享规格与测试夹具，供未来 iOS `WKUserScript` 复用。

### 3.4 Rust 优先边界矩阵（性能最大化且不牺牲交互）

| 能力域 | 实现语言 / 载体 | 选择理由 |
|---|---|---|
| 广告拦截引擎（`miru-adblock`） | **Rust（`libmiru_core`）** | FlatBuffers 亚毫秒冷启动（实测 5k 规则 `0.31 ms`）、微秒级请求匹配（`2.00 µs`），直挂 Chromium C++ IO 线程零 JNI 开销 |
| 媒体嗅探分类 & 清晰度流头解析（`miru-media`） | **Rust（`libmiru_core`）** | 零 GC 字节流解析（HLS/DASH、TS/H.264/H.265 SPS、MP4 `stsd`、VAST），与 Chromium C++ 网络层和未来 iOS 共享一套实现 |
| URL / Origin / Referrer / 缓存键策略（`miru-url`） | **Rust（`libmiru_core`）** | 统一 PSL 域名与安全边界判定，消除 Kotlin / JS / C++ / Swift 多份重复逻辑与语义漂移 |
| 历史/书签 FTS5 检索与地址栏补全打分（`miru-storage`，Phase C3） | **Rust（`libmiru_core`）** | 毫秒级中文/域名模糊与前缀匹配，无主线程 GC 抖动，输入跟手度更高 |
| 多分段下载与校验核心（`miru-download`，Phase C3） | **Rust 核心 + Android 原生服务壳** | `tokio` + `rustls` 高吞吐零拷贝分块写入 Android SAF/MediaStore `fd`；通知、网络监听与系统生命周期仍归 Kotlin |
| 浏览器外壳、手势、弹簧动效与无障碍 | **Kotlin + Jetpack Compose（iOS 用 SwiftUI/UIKit）** | 保持系统级帧同步、IME 键盘协调、TalkBack 无障碍与原生手势物理手感，绝不降级为自绘跨端壳 |
| 网页内核渲染与合成 | **Chromium C++（去 Google 化）** | 复用 Blink / V8 / Skia / Viz 成熟移动能力，通过极薄 `extern "C"` 静态链接 `libmiru_core.a` |
| 视频硬解、音频焦点、PiP 与 GPU 超分 | **Media3 `MediaCodec` + OpenGL ES 3.0 GLSL（iOS 用 AVPlayer + Metal）** | 零拷贝 GPU Surface 渲染链路，视频帧严禁经过 JNI/FFI 内存拷贝 |

---

## 4. 分阶段实施路线与阶段门

严格按序推进，上一阶段核心门未满足前不跨阶段宣称完成。

### Phase C0 · 引擎解耦与接口抽象（Windows 本地立即推进）

**目标**：在不依赖 Linux 构建机的前提下，先把 Miru 上层与 GeckoView / Mozilla Android Components 彻底解耦，并保持 App 在 Windows 上持续可编译、可运行、单测全绿。

- 抽出纯 Kotlin 模块 `:browser:engine-api`，定义稳定契约：
  - 标签与会话状态模型（替代直接暴露 AC `BrowserState` / `TabSessionState` 给 `:feature:*` 与 `:browser:toolbar`）
  - 导航控制、会话持久化桥接、查找、桌面模式、缩放、站点安全/证书信息
  - 站点权限与网页提示事件模型（弹窗、文件/目录上传、表单选择、日期时间颜色、外部 App 确认、通知、自动播放、受保护内容、客户端证书）
  - 下载拦截请求与按 Profile 隔离的 Cookie 读取接口
  - 广告拦截控制器接口（总开关、本页/累计计数、本站白名单、规则列表、元素拾取）
  - 媒体嗅探与页面视频控制接口
- 将现有 GeckoView / AC 实现封装进 `:browser:engine-gecko`（冻结基线适配器）。
- 清理 `:app`、`:browser:toolbar`、`:feature:*` 中的全部 `mozilla.components.*` / `geckoview.*` 直接生产导入。
- 建立统一 Rust 原生核心 Workspace（`native/` → 单一 `libmiru_core`），首批落地并验证三个核心 crate：
  - `miru-adblock`（基于 `adblock-rust` 0.13.6，含 FlatBuffers 快照、Cosmetic、本站白名单、自定义规则、C FFI 与 JNI 桥）；
  - `miru-media`（媒体 URL/MIME/VAST 分类与去重 + HLS M3U8 `RESOLUTION` / MPEG-TS H.264-H.265 SPS / MP4 `stsd` 流头清晰度解析）；
  - `miru-url`（`siteOrigin`、Referrer 策略、URL/Scheme 安全校验、缓存键签名参数去除）。
- **阶段门（C0 Exit Gate）**：
  - `:app/src/main`、`:browser:engine-api`、`:browser:toolbar` 与全部 `:feature:*` 对 Gecko/AC 零直接编译依赖；
  - `native/` Rust Workspace 通过 `cargo test --release`、`cargo build --release` 与 `aarch64-linux-android` 目标编译检查，且 Kotlin JNI 单测在 JVM 实测通过；
  - Windows 上 `:app:assembleDebug`、Debug/Release Lint（0 错误）与全模块 JVM 单元测试全绿；
  - 冻结的 Gecko 后端在模拟器上通过核心烟雾检查，证明解耦过程未破坏现有可用基线。

### Phase C1 · Linux 构建机 / CI 最小 Chromium `.aar` 与内核壳（外部前提：Linux 构建机 / CI）

**目标**：打通去 Google 化 Chromium 可复现编译流水线，并在 Miru 内跑起最小可用浏览外壳。

- 在 Linux x86_64 构建机或 CI 建立可复现的 Chromium + Cromite 去 Google 化构建脚本（固定上游提交、补丁集、`gn args`、SHA-256 清单），产出 ARM64（及可选 x86_64）预构建 `.aar`。
- 新建 `:browser:engine-chromium` 实现 `:browser:engine-api` 基础浏览契约：
  - 单共享 Chromium 进程初始化（严格限制主进程容器装配，防渲染子进程重复初始化）、多标签与导航栈恢复
  - `OTRProfile` 无痕浏览（零磁盘残留 + `FLAG_SECURE` + 关闭全部无痕标签时销毁 Profile）
  - `BrowserControls` 原生跟手隐藏顶/底工具栏、下拉刷新、网页缩放、页内查找、桌面版 UA、网站图标
  - 历史与书签接入现有 `:core:data` SQLite `BrowsingLibrary`
- **阶段门（C1 Exit Gate）**：
  - Windows 本地 Gradle 仅消费预构建 `.aar` 即可编出 Chromium 变体 APK；
  - 冷启动到 Compose 新标签页可交互 `< 800 ms`，多标签/无痕/工具栏联动与主流站点浏览在真实 ARM64 设备（或 x86_64 模拟器）跑通；
  - 抓包/代码审计确认零 Google 遥测、零 GMS、零 Sync 网络请求。

### Phase C2 · 原生 `adblock-rust` 与 C++ 网络层视频嗅探

**目标**：让 Miru 两个核心差异化能力（开箱即用广告拦截 + 网页视频一键原生播放）在 Chromium 上达到甚至超过 v1.0 体验。

- 将统一 Rust 核心 `libmiru_core.a` 静态链接进 Chromium C++ `URLLoaderThrottle` / `NavigationThrottle`，在单次网络回调中零 JNI 往返同时执行 `miru-adblock` 拦截判定与 `miru-media` 视频嗅探分类；接入默认过滤列表内置快照与后台定时更新。
- 对接原生 Compose 盾牌面板（本页计数、累计统计、本站开关与自动刷新、例外管理、过滤列表开关、原生元素拾取）。
- 将 Chromium C++ 网络层视频嗅探、Rust 流头清晰度解析（`miru-media`）与轻量页面脚本接入 `:feature:media-detect`，直连现有 `:feature:player`（暂停网页视频、续接时间点、内存请求头传递、401/403 刷新 Cookie 重试、网页内长按倍速）。
- **阶段门（C2 Exit Gate）**：
  - 冷启动恢复含广告测试页实现首屏零漏拦，无任何启动等待超时；
  - 本地 MockWebServer 与真实站点验证 `d3ward` 拦截率、本站白名单切换与元素拾取；
  - 本地与真实视频站点验证 HLS / DASH / MP4 嗅探（含子 iframe 与带 Referer/Cookie 鉴权）、时间衔接误差 `< 2 s` 与无痕严格隔离。

### Phase C3 · 功能对齐与隐私 / 安全闭环

**目标**：补齐所有日常浏览必需的引擎能力，达到可完全替代冻结 GeckoView 基线的状态。

- 对接 Chromium `DownloadDelegate` 与 `CookieManager` 到下载引擎（普通/无痕 Cookie 严格隔离、认证头不落盘、`blob:`/`data:` 回退处理）；按实测基准推进 `miru-storage`（SQLite FTS5 地址栏/历史毫秒级检索）与 `miru-download`（`tokio` + `rustls` 多分段零拷贝下载核心 + Android `DownloadService` / SAF 文件描述符桥），在不牺牲系统后台通知与交互可靠性的前提下消除 JVM GC 压力。
- 完整实现 B13 站点权限与网页提示：摄像头/麦克风/定位、通知、自动播放、受保护内容（Widevine）、文件与目录上传、表单单选/多选/日期时间/颜色、弹窗阻止与允许列表、外部 App 确认面板、客户端证书选择与证书查看。
- 接入隐私与安全设置：阻止第三方 Cookie、仅 HTTPS 模式、安全 DNS（DoH 自动/关/自定义）、清除浏览数据（含按站点清理与退出时清理）、长按上下文菜单（链接/图片/视频）、无痕常驻通知。
- 验证通过后将 Chromium 设为默认主线，归档或移除 `:browser:engine-gecko` 与残留 Gecko/uBO 资产。
- **阶段门（C3 Exit Gate）**：
  - `docs/ACCEPTANCE.md` 中所有依赖引擎的 P0/P1 条目在 Chromium 内核上重新逐项验证并记录新证据；
  - 常规自动化回归全绿，无遗留 Gecko/uBO 打包资源。

### Phase C4 · 真机验收、体积收敛、安全基线与正式发布

**目标**：交付可在真手机日常主力使用的 Miru v2.0 正式发布版。

- **真机日常与性能验收**：在 ARM64 实体手机上完成国内/国际常用网站浏览与登录（Google、YouTube、Bilibili、知乎、GitHub、淘宝移动版）、90/120 Hz 滚动、内存/发热/续航、Anime4K / FSR 超分实测、TalkBack 与 1.3x 大字号核对。
- **体积与优化门**：ARM64 单 ABI Release APK 实测 `< 150 MB`，通过 R8 混淆、原生符号剥离、语言资源按需裁剪与 16 KB 页对齐校验。
- **上游安全更新机制**：固化 Chromium Stable 安全版本定期 rebase 与 `.aar` 自动重建 SOP（明确补丁冲突检查与回滚步骤）。
- **合规与签名**：完成 Chromium（BSD-3-Clause）、Cromite 补丁许可、`adblock-rust`（MPL-2.0）、内置过滤列表及既有组件（Media3 / Compose / Anime4K / FSR）的最终分发许可审查，并使用维护者正式签名发布。

---

## 5. 关键风险与应对

| 风险 | 说明 | 缓解策略 |
|---|---|---|
| C-R1 上游安全更新成本 | Chromium 约每 4 周发版并含高频安全修复，长期滞后会带来安全隐患 | 严格控制 Miru C++ 补丁体积；紧跟 Cromite 发布节奏做 rebase，把 C++ 编译和 `.aar` 打包放进可一键触发的 Linux CI |
| C-R2 极端反广告站点拦截差异 | 原生 `adblock-rust` 没有 uBO 完整动态过滤与极少数复杂 scriptlet | 及时同步 `adblock-rust` 与 uBO/AdGuard 规则及 scriptlet 资源；实事求是标示为「Miru 原生广告拦截」，不冒称运行 uBO |
| C-R3 Compose 与 Chromium 视图互操作 | SurfaceView/TextureView 叠加层、IME 键盘顶起、底部地址栏与 `BrowserControls` 联动易出现闪烁或裁切 | 在 Phase C1 尽早用真机验证顶/底工具栏、滚动隐藏、键盘弹出和底部面板叠加 |
| C-R4 安装包体积膨胀 | Chromium 原生库 + V8 + ICU 体量较大，容易突破预期 | 仅打包 ARM64 单 ABI，编译期裁剪不需要的 Chromium 特性（PDFium/打印/Chrome Sync/远端组件等）与无关语言包，紧盯 `< 150 MB` 红线 |
| C-R5 网页视频反爬 / DRM | 加密视频（Widevine）或强校验签名流无法交给外部播放器 | 保持与 v1.0 一致的优雅降级：401/403 刷新一次最新 Cookie，仍失败或遇 DRM 时清晰提示并回到网页播放（保留网页内长按倍速） |
| C-R6 构建机与磁盘资源 | Chromium 源码与构建目录占用 > 200 GB，无法在 Windows 日常环境频繁全量重编 | 源码编译与 `.aar` 产物严格隔离在 Linux 构建机 / CI；Windows 仓库继续遵守 `AGENTS.md` 中 `artifacts/` < 3 GB 的清理规则 |
