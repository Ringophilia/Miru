# Miru 浏览器 · 产品与实现规范 v1.0

> 读者：负责实现的工程师 / AI。本文是唯一需求来源，未写明的细节按「Chrome for Android 的做法」处理。
> 平台：**Android 先行**，iOS 在 Android 达到 M4 后启动（见第 14 节）。
> 名称：Miru（見る，"看"）。包名 `app.miru.browser`。
> 参考项目：`G:\Projects\Ringo-Cove`（Flutter 影视客户端），其播放器手势、超分辨率、清晰度探测可直接移植，见第 13 节。

优先级标记：

```text
P0  首个可用版本必须有
P1  第二轮完成
P2  有余力再做
```

---

## 目录

1. 产品定位与设计原则
2. 技术选型
3. 架构与模块
4. 信息架构与页面清单
5. 用户交互（逐页面）
6. 主题与 Styling
7. Animation 动效规范
8. 播放器详细规格
9. 广告拦截规格
10. 下载规格
11. 功能总表（含验收标准）
12. 数据、隐私与性能
13. 从 Ringo-Cove 复用清单
14. iOS 规划
15. 里程碑与测试
16. 风险与待验证项

---

## 1. 产品定位与设计原则

一句话：**像 Chrome 一样干净的浏览器，自带不需要配置的广告拦截，网页里的视频能一键交给一个好用的原生播放器。**

设计原则（冲突时按顺序取舍）：

1. **少即是多。** 主界面只有地址栏、标签数、菜单三个元素；新标签页只有 Logo、搜索框、快捷方式。不做信息流、不做新闻、不做运营位。
2. **默认就对。** 广告拦截开箱即用、无需注册；所有设置都有合理默认值，普通用户永远不必打开设置。
3. **视频是一等公民。** 检测到视频时出现轻量入口，不打扰；进入播放器后手势与主流视频 App 一致（长按倍速、双击快进、横滑拖动）。
4. **一切操作可撤销。** 关标签、删下载、清记录都用 Snackbar「撤销」，而不是确认弹窗（唯一例外：删除下载的同时删除文件、清除全部浏览数据）。
5. **动效服务于空间关系。** 动画告诉用户东西从哪来、到哪去；不做装饰性动画；尊重系统「移除动画」设置。

---

## 2. 技术选型

### 2.1 结论

| 层 | 选择 | 许可 | 理由 |
|---|---|---|---|
| 网页引擎 | **GeckoView**（Firefox 内核，Maven 包 `org.mozilla.geckoview:geckoview`） | MPL-2.0 | Android 上唯一能**完整运行 uBlock Origin**（MV2，含 webRequest 阻断、脚本注入、元素拾取）的可嵌入内核 |
| 浏览器基础组件 | **Mozilla Android Components**（`browser-engine-gecko`、`browser-state`、`browser-session-storage`、`feature-tabs`、`feature-session`、`feature-downloads`、`feature-prompts`、`feature-sitepermissions`、`feature-webextensions`、`feature-findinpage`、`feature-contextmenu`、`feature-media`、`feature-pwa`、`browser-storage-sync`（历史/书签）、`feature-search`） | MPL-2.0 | Firefox for Android 本身就建在这上面，标签、会话恢复、权限、下载、弹窗、右键菜单都是现成的 |
| 骨架参考 | `mozilla-mobile/reference-browser`（Android Components 的官方最小浏览器样例） | MPL-2.0 | **以它为起点 fork**，替换全部 UI；不要 fork Fenix（Firefox 正式版），体量太大、UI 改造成本高 |
| UI | Kotlin 2.x + **Jetpack Compose** + Material 3 | Apache-2.0 | 浏览器 Chrome 外壳与所有原生页面（新标签页、下载、设置、播放器控制层）全部用 Compose |
| 广告拦截 | **uBlock Origin**（AMO 签名版 `.xpi`，随 APK 内置，首启静默安装） | GPL-3.0 | 用户指定；GeckoView 上与 Firefox 桌面版同等能力 |
| 播放器 | **AndroidX Media3 ExoPlayer**（`media3-exoplayer`、`-hls`、`-dash`、`-datasource-okhttp`、`-session`、`-ui` 仅取用 `PlayerView` 的 Surface 部分） | Apache-2.0 | HLS/DASH/MP4/FLV、磁盘缓存（`SimpleCache`）、离线下载（`DownloadManager`）都是官方能力 |
| 超分辨率 | OpenGL ES 3.0 渲染链 + Anime4K v4 / AMD FSR 1.0 着色器（**从 Ringo-Cove 移植**） | MIT | 已在 Ringo-Cove 验证 |
| 网络（下载、探测） | OkHttp 4/5 | Apache-2.0 | 多线程分段下载、断点续传 |
| 本地存储 | Room（下载记录、播放进度）、DataStore（设置） | Apache-2.0 | |
| 依赖注入 | Hilt 或手写 `AppContainer`（二选一，推荐手写，体量小） | | |
| 图片 | Coil 3 | Apache-2.0 | 网站图标、缩略图 |

最低版本：`minSdk 26`（Android 8.0），`targetSdk` / `compileSdk` 取当前最新稳定版。ABI：`arm64-v8a` 必须，`armeabi-v7a`、`x86_64`（模拟器）可选。GeckoView 每个 ABI 约 +40–60 MB，**发布用 AAB / 按 ABI 拆分 APK**。

### 2.2 为什么不用其他方案（实现者不要再回头讨论）

```text
系统 WebView / Chromium 内核   不能装扩展，uBO 无法运行；只能用规则库自己实现，达不到 uBO 的效果
Fork Chromium（Kiwi/Bromite 路线） 编译一次几十 GB、几小时；Chromium 已移除 MV2，uBO 完整版不可用
Fork Fenix                      模块数百个，UI 与"像 Chrome 一样简洁"相去甚远，改造成本高于从 reference-browser 起步
Flutter                         没有成熟的 GeckoView 嵌入方案；浏览器外壳对平台交互（键盘、系统返回、画中画）要求高，原生更稳
```

### 2.3 uBlock Origin 的集成方式

- 从 addons.mozilla.org 下载**官方签名**的 uBO `.xpi`（固定版本，记录 SHA-256），放入 `app/src/main/assets/extensions/ublock_origin.xpi`。
- 首次启动：复制到 `filesDir/extensions/` 后调用 `WebExtensionController.install("file://...")`，安装完成后设置 `allowedInPrivateBrowsing = true`。
- **不要用 `ensureBuiltIn()` 安装 uBO**：社区报告该方式对 uBO 的权限声明有兼容问题（见第 16 节）。`ensureBuiltIn()` 只用于我们自己的 `miru-bridge` 扩展（第 3.3 节）。
- 升级：由 Android Components 的 `AddonUpdater` 走 AMO 自动更新；同时每次发布 Miru 新版本时更新内置 xpi，安装时若内置版本更高则覆盖安装。
- 许可：未来若随应用分发 uBO，须核实实际采用版本的 GPL 条款、对应源码与构建信息、过滤列表等素材的许可，并保留原始声明。独立聚合还是组成单一程序取决于实际集成方式，不能仅凭“扩展”或“未修改”就断言 Miru 不受 GPL 义务影响。当前 M0 未打包 uBO。**分发含 uBO 的版本前须另行完成合规审查**。

---

## 3. 架构与模块

### 3.1 Gradle 模块

```text
:app                    Application、MainActivity、导航、AppContainer
:core:design            设计 token、主题、通用 Compose 组件（第 6 节全部在这里）
:core:common            工具、Result 类型、日志
:core:data              Room 数据库、DataStore、仓库类
:browser:engine         GeckoRuntime 初始化、Engine 配置、扩展安装（uBO + miru-bridge）
:browser:toolbar        地址栏（Compose 实现，不用 AC 自带的 BrowserToolbar View）
:feature:ntp            新标签页
:feature:tabs           标签页切换器
:feature:menu           主菜单
:feature:history        历史
:feature:bookmarks      书签
:feature:downloads      下载页 + 下载引擎
:feature:adblock        拦截计数、盾牌面板、站点白名单
:feature:media-detect   视频嗅探（与 miru-bridge 扩展通信）
:feature:player         原生播放器（ExoPlayer、手势、控制层、缓存、离线）
:feature:superres       GL 超分渲染链 + 着色器 + 性能测试
:feature:settings       设置
```

依赖方向：`feature:* → core:*`，`feature` 之间不互相依赖，跨模块跳转统一走 `:app` 里的 `Navigator` 接口。

### 3.2 运行时结构

```text
MainActivity (单 Activity)
 ├─ BrowserScreen (Compose)
 │   ├─ Toolbar (地址栏/盾牌/视频胶囊/标签数/菜单)
 │   ├─ EngineView (GeckoView, AndroidView 包裹)  ← 当前标签
 │   └─ NTP (当前标签 URL == about:newtab 时显示 Compose 页面，不加载 Gecko)
 ├─ TabsTray / Menu / Sheets (Compose overlay)
 ├─ Downloads / History / Bookmarks / Settings (Compose 全屏路由)
 └─ PlayerActivity (独立 Activity，横屏、画中画、沉浸式)

BrowserStore (Android Components 的 Redux 风格状态)  ← 唯一的标签/会话状态源
PlayerService (Media3 MediaSessionService)            ← 后台播放、通知栏控制
DownloadService (前台服务)                             ← 普通文件下载
Media3 DownloadService                                 ← 视频离线缓存
```

**播放器用独立 Activity**：方便单独声明 `supportsPictureInPicture`、`configChanges`、沉浸模式和屏幕方向，不影响浏览器主界面状态。

### 3.3 miru-bridge 内置扩展

我们自己写一个 WebExtension，用 `ensureBuiltIn("resource://android/assets/extensions/miru-bridge/", "bridge@miru.app")` 安装，通过 Native Messaging（`browser.runtime.connectNative("miru")` ↔ `WebExtension.MessageDelegate` / `PortDelegate`）与原生层通信。职责：

```text
background.js
  · webRequest.onHeadersReceived 观察所有请求，按 Content-Type / 扩展名识别媒体：
      application/vnd.apple.mpegurl, application/x-mpegurl, .m3u8  → HLS
      application/dash+xml, .mpd                                  → DASH
      video/*, .mp4 .webm .mkv .flv .mov                           → 直链
      audio/* （仅当 size > 1MB）                                  → 音频
    忽略：< 200KB 的 video/*（预览片段）、分片（.ts .m4s .aac 不单独上报）、已知广告域（交给 uBO 已拦截的不会到这里）
  · webRequest.onBeforeSendHeaders 记录该媒体请求的 Referer / Origin / User-Agent / Cookie，随检测结果上报
  · 按 tabId 去重（URL 去掉 query 后相同视为同一资源，保留最新的完整 URL）
content.js（所有 frame，document_idle）
  · 扫描 <video>/<audio>，上报 currentSrc、duration、videoWidth/Height、是否在播放、是否 MSE(blob:)
  · 监听 play / fullscreenchange，上报「页面开始播放视频」「进入全屏」事件
  · 网页内长按倍速（第 5.8 节），设置项关闭时不注入
  · 接收原生指令：暂停页面视频（交给 Miru 播放器时调用）、读取当前播放时间
```

`blob:` 地址（MSE）本身不可交给原生播放器，必须依赖 background.js 抓到的 m3u8 / mpd / mp4 原始请求。

---

## 4. 信息架构与页面清单

| 路由 | 页面 | 说明 |
|---|---|---|
| `about:newtab` | 新标签页 | Compose 原生页面 |
| （网页） | 浏览页 | GeckoView |
| `miru://tabs` | 标签页切换器 | 全屏覆盖层 |
| `miru://downloads` | 下载内容 | 也可在地址栏输入 `miru://downloads` 打开 |
| `miru://history` | 历史记录 | |
| `miru://bookmarks` | 书签 | |
| `miru://settings` | 设置 | 二级页面按第 5.11 节 |
| `miru://player?src=…` | 播放器 | 也处理外部 App 分享来的视频 Intent、本地文件 |
| `miru://adblock` | 广告拦截详情 | 统计 + 白名单 + 进入 uBO 控制面板 |

Intent 处理：

```text
ACTION_VIEW http/https            → 新标签打开（申请成为默认浏览器）
ACTION_VIEW video/* audio/*        → 直接进入播放器
ACTION_VIEW application/x-mpegurl  → 直接进入播放器
ACTION_SEND text/plain（含 URL）    → 新标签打开；纯文字 → 搜索
ACTION_WEB_SEARCH                  → 搜索
```

---

## 5. 用户交互（逐页面）

所有尺寸单位为 dp。所有可点区域 ≥ 48×48。

### 5.1 启动

- 冷启动直接进入**上次的标签**（会话恢复）；没有标签时进入新标签页。
- **不显示启动页/广告页**。使用 Android 12 SplashScreen API，图标停留不超过系统默认时长。
- GeckoRuntime 在 `Application.onCreate` 中**异步**创建；新标签页是 Compose 原生页面，不需要等待 Gecko 就绪，保证冷启动到可交互 < 800 ms。
- 首次启动：一屏引导（可跳过）：「Miru 已为你开启广告拦截」+「设为默认浏览器」按钮（调 `RoleManager.ROLE_BROWSER`）。不要求登录，不弹隐私协议以外的任何东西（若上架需要隐私政策，用底部小字链接）。

### 5.2 新标签页（NTP）

布局（竖屏，自上而下）：

```text
状态栏
  (留白 弹性，约屏高 18%)
  Miru 字标（Logo）               高 40，居中
  (24)
  搜索框（胶囊）                   高 52，左右边距 24，圆角全圆
     [🔍 图标] 搜索或输入网址 ......... [🎤 语音]（P1）
  (28)
  快捷方式网格 4 列 × 2 行          每项 64 宽：圆形图标 48 + 文字 12sp 单行省略
  (24)
  继续观看（仅当有未看完的视频时出现，P1）
     横向滑动卡片：缩略图 16:9 宽 160 + 进度条 + 标题 + 站点
  (弹性)
底部工具栏（与浏览页相同）
```

交互：

- 点搜索框 → 地址栏获得焦点（共享元素过渡：搜索框形变为顶部地址栏，第 7 节）。**新标签页的搜索框和地址栏是同一个输入状态**。
- 快捷方式：默认取访问最多的 8 个站点；首次安装预置 Google、YouTube、Bilibili、Wikipedia、GitHub、知乎、微博、百度（按系统语言调整，中文环境用中文站点）。
- 长按快捷方式 → 菜单：在新标签打开、在无痕标签打开、编辑、移除。移除 → Snackbar 撤销。
- 最后一格永远是「+ 添加」（数量 < 8 时）。
- 背景：纯色 `surface`，无壁纸（P2 可选壁纸）。
- 无痕模式的 NTP：背景 `incognitoSurface`，Logo 换成无痕图标 + 一行说明「无痕模式不会保存浏览记录、Cookie 和表单数据。下载的文件会保留。」，不显示快捷方式和继续观看。

### 5.3 地址栏（Omnibox）

默认在**顶部**，设置中可改为**底部**（Chrome 同款选项）。高度 56，内部胶囊 44。

未聚焦状态：

```text
[ 🔒/ⓘ  example.com            🛡12 ] [▶2] [ 3 ] [ ⋮ ]
   ^站点信息  ^域名（不显示协议和路径）  ^拦截数  ^视频胶囊 ^标签数 ^菜单
```

- 域名显示：只显示可注册域名（eTLD+1）加子域，例如 `m.bilibili.com`；搜索结果页显示搜索词（Chrome 的「查询保持」）。
- 🔒/ⓘ：点开站点信息底部面板：连接安全性、证书、权限（摄像头/位置/通知…）、Cookie 与站点数据、本站广告拦截开关。
- 🛡 盾牌 + 数字：当前页面被 uBO 拦截的请求数（取 uBO browserAction 的 badge 文字）。0 时只显示灰色盾牌。点击 → 广告拦截面板（第 5.7 节）。
- ▶ 视频胶囊：仅当页面检测到可播放视频时出现（第 5.8 节），数字为视频数量。
- 标签数：圆角方框内数字，> 99 显示 `:D`（Chrome 彩蛋，可选）。点击 → 标签页切换器。
- 页面加载中：地址栏底边出现 2 dp 进度条（第 7 节）。
- **滚动隐藏**：网页向下滚动时工具栏跟手隐藏，向上滚动跟手出现（Android Components `DynamicToolbar` 机制，配合 `EngineView.setDynamicToolbarMaxHeight`）；页面加载、到达页面顶部、地址栏获焦时强制显示。
- **横滑工具栏切换标签**（Chrome 手势）：在工具栏上左右滑动，切换到相邻标签，页面截图跟手平移。
- 长按地址栏 → 复制链接 / 粘贴并前往 / 分享。

聚焦状态（编辑模式）：

```text
[ ← ] [ 输入框（全选当前 URL）           ✕ ]
————————————————————————————————————————————
你复制的链接   https://...（剪贴板含 URL 时，且 Android 13+ 无剪贴板提示问题时才显示）
建议列表：
  🔍 搜索建议（来自搜索引擎 suggest 接口，最多 5 条）
  🕘 历史记录匹配（最多 3 条）
  ☆ 书签匹配（最多 3 条）
  ⇄ 已打开的标签匹配「切换到此标签」
```

- 每条建议右侧有「↖」填入按钮：把建议填入输入框继续编辑。
- 输入时**内联自动补全**：域名前缀匹配历史时，补全部分高亮选中（Chrome 行为）。
- 回车：是 URL 则打开，否则用默认搜索引擎搜索。
- 搜索引擎：Google（默认）、Bing、DuckDuckGo、百度、自定义（URL 模板含 `%s`）。中文系统首次启动也默认 Google，用户可改。
- 返回键/点击 ← → 取消编辑，恢复原 URL。

### 5.4 网页浏览

- **下拉刷新**：页面在顶部时下拉出现圆形刷新指示器（Material 3 `PullToRefresh`），由 GeckoView overscroll 事件驱动（`feature-session` 的 `SwipeRefreshFeature`）。
- **边缘返回**：使用系统返回手势；可后退时后退，不能后退时关闭当前标签（若此标签由其他 App 打开则返回那个 App，Chrome 行为）。
- **长按链接/图片** → 底部面板上下文菜单：
  - 链接：在新标签中打开、在无痕标签中打开、复制链接地址、复制链接文字、下载链接、分享链接
  - 图片：在新标签中打开图片、下载图片、复制图片、分享图片、用此图搜索（P2）
  - 视频：**用 Miru 播放器播放**、下载视频、复制视频地址
- **全屏网页视频**：GeckoView 进入全屏时隐藏系统栏和工具栏；同时在右上角显示 3 秒的悬浮胶囊「在 Miru 播放器中打开」（第 5.8 节）。
- **页内查找**：菜单 → 在网页中查找 → 工具栏替换为查找栏（输入框 + 「2/15」+ ↑ ↓ + ✕）。
- **桌面版网站**：菜单中复选框，切换 UA 并重新加载。
- **文字缩放**：设置 → 外观 → 网页缩放（50%–200%）。
- **外部 App 链接**（intent://、market://、weixin:// 等）：底部提示「在 X 中打开？」+ 打开/取消，不自动跳转。
- **弹窗/新窗口**：uBO 已拦截大部分；剩余的弹窗被阻止时底部 Snackbar「已阻止弹出窗口 · 始终允许」。
- **权限请求**：底部面板（Android Components `feature-sitepermissions` + `feature-prompts`），样式按第 6 节重绘。

### 5.5 标签页切换器

- 全屏覆盖层，**2 列网格**，卡片宽高比与屏幕一致（缩略图为页面截图），卡片上方一行：网站图标 + 标题 + ✕。
- 顶部栏：`[ + ]` 新标签 · 分段按钮 `普通 | 无痕` · `[ ⋮ ]`（关闭所有标签、选择多个标签）。
- 当前标签卡片有 2 dp 主题色描边。
- 卡片**左右滑动关闭**（超过 40% 宽度或速度 > 1000 dp/s），Snackbar「已关闭 X · 撤销」。
- 长按卡片 → 进入拖拽排序（P1）。
- 点卡片 → 卡片放大回到全屏（第 7 节容器变换）。
- 标签分组：P2。
- 关闭所有标签：需要 Snackbar 撤销（5 秒内可全部恢复）。

### 5.6 主菜单

点击 ⋮ 从右上角展开（弹出菜单，宽 260）。结构参照 Chrome：

```text
第一行图标按钮： [→ 前进] [☆ 书签] [⬇ 下载此页] [ⓘ 页面信息] [⟳ 刷新/✕ 停止]
———
新标签页
新无痕标签页
———
历史记录
下载内容                  （有进行中的下载时右侧显示进度圆环）
书签
最近的标签页（P2）
———
分享…
在网页中查找
添加到主屏幕
桌面版网站              [☐]
———
广告拦截                  本页 12 · 共 3,481
设置
```

### 5.7 广告拦截面板（点盾牌）

底部面板（高度自适应，最大 70% 屏高）：

```text
┌─────────────────────────────────┐
│ 🛡  example.com                 │
│     本页已拦截 12 项              │
│                                 │
│ [ 在此网站上拦截广告     ⬤ ]    │ ← 开关；关闭后自动刷新页面
│                                 │
│ [ ✂ 屏蔽页面元素 ]               │ ← 调起 uBO 元素拾取器，面板收起
│ [ 📊 查看详细记录 ]              │ ← 在新标签打开 uBO logger（P1）
│ [ ⚙ 高级设置 ]                   │ ← 新标签打开 uBO dashboard
│                                 │
│ 累计已拦截 3,481 项 · 节省约 120 MB（P1） │
└─────────────────────────────────┘
```

- 「在此网站上拦截广告」开关的实现：调用 uBO 的站点白名单。uBO 没有对外 API，做法二选一（实现者验证后选定）：
  - A（推荐）：在面板中直接嵌入 uBO 自带的移动端弹出页 `popup-fenix.html`（GeckoView 的 `WebExtension.Action` popup，Android Components 已支持），我们的 Compose 外框只负责标题与底部按钮；
  - B：通过 miru-bridge 向 uBO 页面注入脚本修改 `netWhitelist`（脆弱，不推荐）。
- 拦截数来自 uBO 的 `browserAction` badge（`WebExtension.ActionDelegate.onBrowserAction` → `Action.badgeText`）。累计数由我们自己按页面累加存储。
- 默认规则列表：保持 uBO 默认（uBO 内置列表 + EasyList + EasyPrivacy + Peter Lowe + 按系统语言自动启用区域列表，中文环境即 AdGuard Chinese / EasyList China）。**额外默认开启**：uBO Annoyances 中的「Cookie 提示」列表（减少 Cookie 横幅打扰）。

### 5.8 视频嗅探与接管

**入口一：地址栏视频胶囊 ▶N**

- miru-bridge 检测到媒体后，地址栏出现 ▶ 胶囊（缩放 + 淡入，第 7 节）。同一页面后续新增视频时数字滚动更新。
- 点击 → 底部面板「本页视频」：

```text
本页视频（3）
┌──────────────────────────────────────────────┐
│ [缩略图] 第12集 正片            HLS · 1080P · 45:12 │
│          v.example.com                       │
│          [ ▶ 播放 ]  [ ⬇ 缓存 ]  [ ⧉ 复制链接 ]   │
├──────────────────────────────────────────────┤
│ ...                                          │
└──────────────────────────────────────────────┘
```

- 标题：优先页面 `<title>` / `og:title`；同页多个视频时附加「视频 1/2/3」。
- 清晰度 / 时长：后台探测（第 8.9 节），探测中显示「···」。
- 排序：正在播放的 > 时长最长的 > 其他；时长 < 30 秒的（多为广告/预告）折叠到底部「其他 2 个短视频」。
- 「播放」→ 暂停网页里的视频（通过 miru-bridge），进入 PlayerActivity，**从网页视频当前的播放位置继续**。
- 「缓存」→ 加入下载队列（视频离线，第 10 节），Snackbar「已加入下载 · 查看」。

**入口二：网页视频开始播放 / 进入全屏时的提示胶囊**

- 页面里的视频**开始播放**时，在视频区域底部之上（若取不到坐标则屏幕底部工具栏之上）出现胶囊「▶ 用 Miru 播放器」，停留 5 秒自动消失。同一个页面只提示一次。
- 进入网页全屏时，在右上角出现同样的胶囊 3 秒。
- 设置项「自动用 Miru 播放器打开网页视频」（默认关）：开启后，页面视频开始播放即自动接管（DRM 视频、直播除外）。

**入口三：长按视频 / 链接** → 上下文菜单「用 Miru 播放器播放」。

**不可接管的情况**：DRM（EME 已激活）、`blob:` 且未抓到原始清单、站点验证 Referer/Cookie 失败。处理：播放器起播失败时提示「该视频无法在 Miru 播放器中播放，已返回网页」，自动关闭播放器并恢复网页播放。

**网页内长按倍速（P1）**：不接管也能用。content.js 在 `<video>` 元素上监听 pointer 事件：按住 500 ms → `playbackRate = 设置的长按倍速`，在视频上方中间显示胶囊「2.0x ▶▶」；松手恢复原速度。只在视频正在播放时生效；设置项「网页视频长按倍速」默认开。若站点自己有长按行为（如 B 站移动版），以站点为准（检测到 `contextmenu`/`touchstart` 被 `preventDefault` 时不介入）。

### 5.9 下载内容页

见第 10 节的完整规格。交互要点：

```text
顶部栏： ←  下载内容                       [🔍] [⋮]
筛选 Chip（横向滚动）： 全部 · 视频 · 音频 · 图片 · 文档 · 压缩包 · 安装包 · 其他
存储条： 已用 2.3 GB · 设备剩余 41 GB                    （细条，P1）
列表（按日期分组：今天 / 昨天 / 本周 / 更早）
  [类型图标或缩略图]  文件名.mp4                    [⋮]
                     12.4 MB / 340 MB · 2.1 MB/s · 剩 2 分钟
                     ▬▬▬▬▬▬▬░░░░░░░░░  （进度条）
                     [⏸]                           （进行中：暂停；暂停中：▶ 继续；失败：⟳ 重试）
```

- 点击完成项：视频/音频 → Miru 播放器；APK → 系统安装器；其他 → 系统「打开方式」。
- 左滑删除 → Snackbar「已删除 · 撤销」（仅删记录，撤销期过后再真正删除文件；设置项「删除记录时同时删除文件」默认开）。
- 长按进入多选模式：顶部栏变为「已选 3 项  [分享] [删除]」。
- ⋮（单项）：打开、分享、复制下载链接、重命名、在文件管理器中显示、删除。
- ⋮（页面）：全部暂停、全部继续、清除已完成记录、下载设置。
- 空状态：居中插图（简单线条下载箭头）+「还没有下载内容」。

**下载确认面板**（点下载链接时，设置「下载前询问」默认开）：

```text
下载文件
[图标]  文件名.zip                     ✎（可改名）
        34.2 MB · example.com
保存到： 下载/Miru                    （点击可改，SAF 选目录）
[取消]                        [下载]
```

下载开始时：底部 Snackbar「正在下载 文件名.zip · 查看」；菜单 ⋮ 图标出现进度小圆点（第 7 节）。

### 5.10 历史记录与书签

- 历史：按日期分组，顶部搜索；每项：网站图标、标题、域名、时间；左滑删除；顶部「清除浏览数据…」入口。
- 书签：文件夹结构（P1），默认一个「移动设备书签」文件夹；星标按钮点亮动画（第 7 节）。
- 两者都使用 Android Components 的 `places` 存储（`browser-storage-sync`）。

### 5.11 设置

一级列表（Material 3 列表项，分组标题 + 分隔留白，不用卡片）：

```text
基本
  搜索引擎                          Google
  默认浏览器                        设为默认 >
  首页与新标签页                    快捷方式数量、继续观看
外观
  主题                              跟随系统 / 浅色 / 深色
  主题色                            林檎红（默认）/ 动态取色（Android 12+）/ 6 个预设色
  地址栏位置                        顶部 / 底部（带示意图的二选一卡片）
  网页缩放                          100%
隐私与安全
  清除浏览数据                      时间范围 + 勾选项
  阻止第三方 Cookie                 开
  仅 HTTPS 模式                     关
  安全 DNS（DoH）                   自动 / 关 / 自定义提供商
  退出时清除数据                    关
广告拦截
  广告拦截                          开（总开关）
  例外网站                          列表，可删除
  过滤规则与高级设置                → 打开 uBO 控制面板
视频与播放器
  自动用 Miru 播放器打开网页视频     关
  网页视频长按倍速                  开
  —— 手势 ——
  双击快进/快退秒数                 10 秒（5/10/15/30）
  长按倍速                          2.0x（1.25–3.0，步长 0.25）
  默认播放速度                      1.0x
  横向滑动范围                      全屏宽度 = 120 秒（60/90/120/180/300）
  左侧上下滑动调亮度                开
  右侧上下滑动调音量                开
  双击中间暂停                      开
  触感反馈                          开
  —— 播放 ——
  记忆播放进度                      开
  自动播放下一集（同页检测到剧集时，P2）  开
  后台播放                          关（开启后退到后台继续出声）
  离开时自动画中画                  开
  默认画面比例                      适应 / 填充裁剪 / 拉伸
  解码方式                          自动 / 硬件 / 软件
  —— 画质 ——
  超分辨率                          关 / 自动 / 动画线条 / 通用
  超分档位                          流畅 / 均衡 / 画质
  测试本机性能                      >
  —— 缓存 ——
  边播边存上限                      1 GB（256 MB / 512 MB / 1 / 2 / 4 / 8 GB）
  预缓冲时长                        60 秒（30/60/120/300）
  已用 438 MB                       [清除播放缓存]
下载
  下载前询问                        开
  下载位置                          下载/Miru
  同时下载数                        3（1–5）
  单文件线程数                      4（1–8）
  视频仅在 Wi-Fi 下下载             关
  删除记录时同时删除文件            开
站点设置                            权限、通知、自动播放、弹出窗口
关于 Miru                           版本、开源许可、uBO 版本
```

子页面使用「共享轴 X」过渡（第 7 节）。所有开关即时生效，无「保存」按钮。

### 5.12 无痕模式

- 独立的标签组；工具栏、NTP 使用无痕配色（第 6 节）。
- 播放器在无痕标签发起时：不记录播放进度、不写入「继续观看」、**不使用磁盘缓存**（改用内存缓冲），离线缓存按钮仍可用但先提示「下载的文件会保留在设备上」。
- 有无痕标签时常驻通知「关闭所有无痕标签」（Chrome 行为）。
- 无痕标签禁止截屏（`FLAG_SECURE`），标签切换器缩略图模糊。

---

## 6. 主题与 Styling

### 6.1 总体风格

- **Material 3**，视觉对标 Chrome for Android 2025–2026 版：大面积纯色表面、胶囊形输入框、圆角卡片、无阴影或极淡阴影、无渐变（播放器除外）。
- 默认使用下面的 **Miru 色板**，颜色取自应用图标：苹果红（主色）、纸色（页面）、墨色（文字）、叶绿（第三色）。**Material You 动态取色**（Android 12+）作为可选项，默认关闭。
- **浏览器外壳跟随系统深浅色；播放器永远是深色。**

### 6.2 颜色 Token

以 Compose `ColorScheme` + 自定义 `MiruColors` 扩展实现。命名使用语义名，禁止在组件里写死色值。

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `primary` | `#BE1625` | `#FFB3AE` | 主操作、链接、开关、进度（苹果红） |
| `onPrimary` | `#FFFFFF` | `#680010` | |
| `primaryContainer` | `#FFDAD7` | `#8F0F22` | 选中 Chip、当前标签描边底 |
| `secondaryContainer` | `#F2E1D9` | `#5D3F3A` | FilterChip 选中、进度条底轨 |
| `tertiary` | `#47643B` | `#A9CE97` | 叶绿，第三色 |
| `surface` | `#FAF5EA` | `#1F1917` | 页面背景、NTP（纸色 / 墨色） |
| `surfaceContainerLow` | `#F6F0E4` | `#241E1B` | 底部面板 |
| `surfaceContainer` | `#F1EADD` | `#2B2421` | 地址栏胶囊、NTP 搜索框、菜单 |
| `surfaceContainerHigh` | `#E8DFD0` | `#382F2B` | 按下态、对话框 |
| `surfaceContainerHighest` | `#E1D7C7` | `#433935` | 开关底轨 |
| `onSurface` | `#2B1E1A` | `#F0E6DE` | 主要文字（墨色） |
| `onSurfaceVariant` | `#6D605A` | `#B5A8A0` | 次要文字、图标 |
| `outline` | `#857770` | `#A0918A` | 输入框描边 |
| `outlineVariant` | `#DDD2C3` | `#4A3F3A` | 分隔线 |
| `error` | `#C4381A` | `#FFB59C` | 下载失败、证书错误（偏橙，与主色红区分） |
| `success` | `#47643B` | `#A9CE97` | 下载完成、安全连接（叶绿） |
| `adblock` | `#47643B` | `#A9CE97` | 盾牌有拦截时的颜色 |
| `incognitoSurface` | `#221D1B` | `#221D1B` | 无痕模式（浅色主题下也用深色） |
| `incognitoContainer` | `#36302C` | `#36302C` | 无痕地址栏 |
| `scrim` | `#000000` 32% | `#000000` 50% | 面板遮罩 |

主题色预设（用户关闭动态取色时可选，替换 `primary` 系列，深色值取对应 Material 色调 80）：

```text
林檎红 #BE1625（默认）   叶绿 #47643B   藏青 #2E4A7D   青 #00897B   紫 #7C4DFF   石墨 #455A64
```

**播放器色板（固定深色）**：

| Token | 值 | 用途 |
|---|---|---|
| `playerBg` | `#000000` | 画面背景 |
| `playerScrimTop` | 黑 0% → 60% 纵向渐变，高 120 | 顶栏底衬 |
| `playerScrimBottom` | 黑 0% → 70% 纵向渐变，高 160 | 底栏底衬 |
| `playerGlass` | `#000000` 60% + 1 dp `#FFFFFF` 12% 描边 | 倍速胶囊、亮度音量指示、提示 |
| `playerPanel` | `#14141B` 92% | 右侧面板 |
| `playerText` | `#F5F5F7` | |
| `playerTextDim` | `#EBEBF5` 70% | |
| `playerTrack` | `#FFFFFF` 24% | 进度条底轨 |
| `playerBuffered` | `#FFFFFF` 45% | 内存已缓冲 |
| `playerCached` | `#FFFFFF` 32% 斜纹（P2）或同 buffered | 磁盘已缓存区段 |
| `playerProgress` | `primary`（深色值） | 已播放 |

### 6.3 字体

- 系统默认字体（Roboto / 系统中文字体），不内置字体文件（控制 APK 体积）。Logo 字标除外：做成矢量图。
- 字号（sp）：

| Token | 大小/行高/字重 | 用途 |
|---|---|---|
| `displaySmall` | 36/44/400 | 不使用（预留） |
| `titleLarge` | 22/28/400 | 页面标题（设置、下载） |
| `titleMedium` | 16/24/500 | 面板标题、列表主文字 |
| `bodyLarge` | 16/24/400 | 地址栏输入、搜索框 |
| `bodyMedium` | 14/20/400 | 列表主文字（密集）、正文 |
| `bodySmall` | 12/16/400 | 列表次要文字、时间 |
| `labelLarge` | 14/20/500 | 按钮 |
| `labelMedium` | 12/16/500 | Chip、快捷方式文字 |
| `labelSmall` | 11/16/500 | 角标、标签数 |
| `playerTime` | 13/16/500，**等宽数字**（`fontFeatureSettings = "tnum"`） | 播放器时间 |
| `playerOverlay` | 15/20/700 | 双击「+10 秒」、倍速胶囊、拖动时间 |
| `playerSeekBig` | 28/34/700，tnum | 横滑拖动时的中央大时间 |

所有数字会跳动的地方（下载速度、进度、拦截数、时间）一律用 tnum 等宽数字，避免抖动。

### 6.4 形状、间距、海拔

```text
圆角： xs 4 · sm 8 · md 12 · lg 16 · xl 28 · full 胶囊
  地址栏胶囊 / NTP 搜索框 / Chip / 倍速胶囊   full
  标签卡片                                   md 12
  底部面板顶部                               xl 28
  菜单 / 对话框                              lg 16 / xl 28
  播放器右侧面板左侧                          lg 16
  快捷方式图标底                             full（圆）
间距栅格：4 的倍数。页面左右边距 16（NTP 搜索框 24）。列表项高：单行 56，双行 72，三行 88。
海拔：外壳全部 0 dp + tonal 区分（Material 3 做法）；菜单与底部面板 tonal level 2；仅悬浮胶囊（视频接管提示）用 6 dp 阴影。
```

### 6.5 图标

- **Material Symbols Rounded**，线框（outlined）风格，weight 400，grade 0，尺寸 24（工具栏）/ 20（列表次要）/ 28（播放器主按钮）/ 56（播放器中央播放键）。
- 选中态用 filled 变体（书签星标、当前筛选 Chip 的勾）。
- 网站图标：16 dp 圆角 4 位图；获取不到时用首字母 + 由域名哈希出的色块。
- 应用图标：纸色底上一颗红苹果，苹果上是一只带眼线挑尾和弯钩睫毛的眼睛（致敬椎名林檎），颜色为苹果红 `#BE1625`、纸 `#FAF5EA`、墨 `#321E19`、叶绿 `#4E6443`；Android 13 主题图标是同一图形的单色剪影，眼白和瞳孔高光镂空。原画 `design/miru-icon-source.png`，由 `tools/icon/trace_icon.py` 描摹生成 `design/miru-icon*.svg` 和 VectorDrawable，改图标时换原画后重新运行脚本。

### 6.6 关键组件规格

**地址栏胶囊**：高 44，背景 `surfaceContainer`，圆角 full，左内边距 12，图标与文字间距 8，文字 `bodyLarge`，聚焦时背景变 `surface` + 2 dp `primary` 描边（深色模式描边为 `primary` 60%）。

**底部面板**：Material 3 `ModalBottomSheet`，顶部拖拽把手 32×4，背景 `surfaceContainer`，最大高度 90% 屏高，内容左右边距 24。

**Snackbar**：浮动式，底边距工具栏上方 8，圆角 sm 8，背景 inverseSurface，带一个文字按钮（撤销/查看），显示 4 秒（含撤销的 6 秒）。

**Chip**：高 32，圆角 sm 8（Material 3 筛选 Chip），选中时 `primaryContainer` + 勾选图标。

**进度条（下载）**：线性，高 4，圆角 2，轨道 `surfaceContainerHigh`，指示器 `primary`；暂停时指示器变 `onSurfaceVariant`。

**播放器进度条**：见第 8.3 节。

---

## 7. Animation 动效规范

### 7.1 Motion Token

全部集中定义在 `:core:design` 的 `MiruMotion` 中，组件只引用 token。

```text
时长
  instant      50 ms   按下态颜色
  short1      100 ms   图标切换、勾选
  short2      150 ms   淡入淡出、进度条加粗
  short3      200 ms   退出类动画、控制层隐藏
  medium1     250 ms   列表重排、Chip 选中
  medium2     300 ms   地址栏聚焦展开、面板进入
  medium3     350 ms   标签切换器容器变换
  long1       450 ms   双击快进涟漪
  long2       500 ms   下载图标飞入菜单

缓动（cubic-bezier）
  standard              (0.2, 0.0, 0.0, 1.0)    常规属性变化
  standardDecelerate    (0.0, 0.0, 0.0, 1.0)    进入
  standardAccelerate    (0.3, 0.0, 1.0, 1.0)    退出
  emphasized            Material 3 emphasized（路径曲线；Compose 用 EaseInOutCubic 近似或自定义 Easing）
  emphasizedDecelerate  (0.05, 0.7, 0.1, 1.0)   重要元素进入（面板、播放器侧栏）
  emphasizedAccelerate  (0.3, 0.0, 0.8, 0.15)   重要元素退出
  spring.default        dampingRatio 0.8, stiffness 380（StiffnessMediumLow）  跟手后的回弹
  spring.bouncy         dampingRatio 0.6, stiffness 500                         星标点亮、胶囊出现
```

### 7.2 规则

- 进入用 Decelerate，退出用 Accelerate，退出时长 ≈ 进入时长 × 0.7。
- **跟手的动画（滑动关闭标签、工具栏隐藏、播放器拖动）在手指离开后一律用 spring 接续当前速度**，不要用固定时长补间。
- 读取 `Settings.Global.ANIMATOR_DURATION_SCALE`：为 0 时，所有位移/缩放动画降级为 short2 交叉淡入淡出；循环动画（倍速胶囊箭头流动、加载指示）改为静态。
- 动画期间不得阻塞输入：用户可以在任何动画过程中发起新操作，新动画从当前值开始（Compose `Animatable` 天然支持）。
- 60/90/120 Hz 均需流畅；任何动画不得在主线程做 I/O、解码图片或布局整个 GeckoView。

### 7.3 浏览器外壳动效清单

| 场景 | 动效 | 时长/缓动 |
|---|---|---|
| NTP 搜索框 → 地址栏聚焦 | 共享元素：搜索框从 NTP 中部形变（位置、宽度、圆角保持 full）到顶部地址栏位置；Logo 和快捷方式同时淡出并上移 16；建议列表从地址栏下方淡入 | medium2 / emphasizedDecelerate |
| 地址栏聚焦（网页中） | 胶囊横向展开覆盖标签数和菜单按钮（它们淡出 + 缩到 0.8）；左侧图标交叉淡变为 ← | medium2 / emphasized |
| 建议列表出现 | 每条依次淡入 + 从 y+8 上移，间隔 20 ms，最多错开 6 条 | short2 / standardDecelerate |
| 页面加载进度条 | 2 dp 条在地址栏底边；进度值用 spring 平滑追赶真实进度；进度 0→10% 立即出现；100% 后保持 150 ms 再淡出 | 淡出 short3 |
| 工具栏滚动隐藏 | 跟手平移；松手后若显示超过一半则 spring 到完全显示，否则到完全隐藏 | spring.default |
| 横滑工具栏切换标签 | 当前页截图跟手平移，相邻标签截图从屏幕边缘跟入；松手过半或速度够则切换 | spring.default |
| 盾牌计数变化 | 数字纵向滚动（旧数字上移淡出，新数字自下而上进入）；从 0 变为 ≥1 时盾牌由灰变 `adblock` 色 | short2 |
| 视频胶囊出现 | 从宽 0 展开到内容宽，图标缩放 0.6→1，同时地址栏域名文字区平滑让位 | medium1 / spring.bouncy |
| 打开标签切换器 | 容器变换：当前页面缩小并移动到其网格卡片位置，圆角 0→12，其他卡片淡入 + 缩放 0.92→1 | medium3 / emphasized |
| 从切换器进入标签 | 上一条的逆过程，从被点卡片放大到全屏 | medium3 / emphasizedDecelerate |
| 关闭标签卡片 | 跟手横移 + 透明度随距离下降；松手关闭则以当前速度滑出；其余卡片用 spring 补位 | spring.default |
| 新建标签 | 新卡片从「+」按钮位置放大展开为全屏 NTP | medium2 / emphasizedDecelerate |
| 菜单展开 | 从 ⋮ 锚点缩放 0.85→1 + 淡入，变换原点为右上角；菜单项不做逐项动画 | short3 / emphasizedDecelerate；关闭 short1 |
| 底部面板 | Material 3 默认：从底部滑入 + 遮罩淡入 | medium2 / emphasizedDecelerate；退出 short3 |
| 页面切换（设置子页、下载、历史） | 共享轴 X：新页从 x+30 dp 淡入，旧页向 x-30 dp 淡出 | medium2 / emphasized |
| 一级全屏页（下载/历史/书签）打开 | 淡入 + 从 y+24 上移（Fade Through） | medium1 |
| 下载开始 | 一个下载箭头图标从屏幕中部（或被点击链接处）沿抛物线飞向 ⋮ 菜单按钮，落下后 ⋮ 上出现 6 dp 进度圆点（`primary`）并轻微弹跳 | long2 / emphasized + spring.bouncy |
| 下载完成 | 列表项进度条淡出，状态图标从进度圆环形变为 ✓（路径动画），文字颜色从次要变为主要 | short3 |
| 书签星标点亮 | 星标 outlined→filled 交叉淡变，缩放 1→1.25→1 | spring.bouncy |
| Snackbar | 从底部 y+16 淡入上移；撤销后淡出 | short3 / standardDecelerate |
| 下拉刷新 | Material 3 PullToRefresh 默认指示器；达到阈值时一次触感反馈 | 系统默认 |
| 无痕模式切换 | 工具栏和 NTP 背景色交叉过渡 | medium1 |
| Chip 选中 | 背景色过渡 + 勾选图标从宽 0 展开 | short2 |
| 列表项删除 | 高度收起 + 淡出，下方项上移 | medium1 / standard |

### 7.4 播放器动效清单

参照 Ringo-Cove `lib/ui/player/player_widgets.dart` 的实现，数值保持一致：

| 场景 | 动效 | 时长/缓动 |
|---|---|---|
| 控制层显示/隐藏 | 顶栏与底栏透明度 0↔1，同时顶栏 y 偏移 -8↔0、底栏 +8↔0；中央播放键缩放 0.9↔1 | 显示 short2 / standardDecelerate；隐藏 short3 / standardAccelerate |
| 控制层自动隐藏 | 播放中无操作 **4 秒**后隐藏；暂停时不自动隐藏 | — |
| 播放/暂停图标 | 两个图标路径形变（AnimatedVectorDrawable 或 Compose 自绘），或交叉缩放淡变 | short2 |
| 双击快进/快退 | 屏幕一侧出现**弧形区域**（宽 36% 屏宽，内侧边缘为二次贝塞尔弧线），白色 16%→10% 透明度渐变消退；内部 3 个 ▶ 依次亮起（亮度按 sin 曲线流动）；下方文字「+10 秒」，连续点击累加为「+20 秒」「+30 秒」，每次点击重播一次动画 | long1（450 ms）/ easeOutCubic；最后一次点击后 750 ms 收起 |
| 长按倍速胶囊 | 顶部居中（距顶 24）胶囊淡入 + 缩放 0.9→1；胶囊内 3 个 ▶ 循环流动（周期 800 ms，第 3 个为主题色）；文字「2.0x 倍速播放中」 | 进入 220 ms / spring.bouncy；退出 short3 |
| 横滑拖动进度 | 屏幕中央玻璃卡片：大号目标时间 `12:34` + 灰色 ` / 45:12`，下方差值 `+01:30`（绿色前进/红色后退可选）；有缩略图时卡片上方显示 160×90 预览图 | 出现 short2；数字实时更新不做动画 |
| 亮度/音量指示 | 屏幕中部偏上的玻璃条（宽 200）：图标 + 5 dp 进度条 + 数值；松手后停留 700 ms 再淡出 | 淡出 short3 |
| 进度条交互 | 手指按下：轨道高度 4→8 dp、thumb 从 0 放大到 16 dp；拖动时 thumb 上方出现时间气泡（+ 缩略图）；松手反向 | short2 / standard |
| 右侧面板（倍速、画质、选集、设置） | 从右侧滑入（宽 360，最大 85% 屏宽），背景遮罩淡入；关闭时滑出 | 进入 320 ms / emphasizedDecelerate；退出 200 ms / emphasizedAccelerate |
| 面板内容切换 | 交叉淡变 | 200 ms |
| 锁屏 | 锁定按钮图标形变（开锁→锁），其余控件全部淡出；锁定状态下单击屏幕只显示锁按钮 2 秒 | short2 |
| 提示 Toast（「已跳过片头」「已切换至硬件解码」） | 左下角玻璃胶囊淡入，停留 2.2 秒后淡出 | short2 / short3 |
| 缓冲中 | 中央 40 dp 圆形不确定进度指示（2.5 dp 线宽，白色）；**缓冲超过 300 ms 才显示**，避免一闪而过；下方显示实时网速 `1.2 MB/s` | 淡入 short2 |
| 下一集提示（P2） | 剩余 30 秒时右下角卡片从右侧滑入 | 360 ms / emphasizedDecelerate |
| 进入播放器 | 从视频胶囊面板/网页视频位置，缩略图放大到全屏（共享元素过渡，Activity 间用 `ActivityOptions.makeSceneTransitionAnimation` 或简化为淡入黑底 + 画面缩放 0.92→1） | medium2 |
| 超分对比分割线 | 拖动时画面左侧显示原画、右侧显示超分，分割线 2 dp 白色 + 圆形把手；标签「原画 | 超分」跟随 | 跟手，无补间 |
| 画中画 | 系统动画；设置 `setSourceRectHint` 为视频画面区域，保证缩放连贯 | 系统 |

### 7.5 触感反馈（Haptics）

```text
长按倍速开始                  HapticFeedbackConstants.LONG_PRESS
双击快进/快退（每次）          CLOCK_TICK
双击暂停/播放                  CONTEXT_CLICK
下拉刷新到达阈值               GESTURE_THRESHOLD_ACTIVATE（API 34+，低版本用 CONTEXT_CLICK）
标签滑动关闭越过阈值            同上
锁屏/解锁                      CONFIRM（API 30+）
```

受设置「触感反馈」开关控制，同时遵守系统触感设置。

---

## 8. 播放器详细规格

### 8.1 输入来源

```text
1. 网页嗅探到的 HLS / DASH / 直链（带 Referer、Origin、User-Agent、Cookie）
2. 外部 App 的 ACTION_VIEW 视频 Intent、本地文件（content:// / file://）
3. 下载页里已离线的视频
4. 地址栏直接输入以 .m3u8 / .mp4 / .mpd 结尾的地址 → 询问「用播放器打开 / 当作网页打开」
```

请求头：ExoPlayer 的 `OkHttpDataSource.Factory` 设置 `setDefaultRequestProperties(headers)`，**清单与分片都带同样的头**。不得改写 m3u8 内的分片地址。

### 8.2 画面布局（横屏）

```text
┌──────────────────────────────────────────────────────────────┐
│ ←  视频标题（单行省略）              1080P  [画质✦] [⋮]        │ 顶栏
│                                                              │
│ [🔒]                     ( ▶ )                    [⧉ 截图 P2] │ 左侧锁按钮 中央播放
│                                                              │
│ ▬▬▬▬▬▬▬▬▬▬●━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━          │ 进度条
│ ▶  ⏭   12:34 / 45:12          倍速  选集  比例  [▭画中画] [⤢] │ 底栏
└──────────────────────────────────────────────────────────────┘
```

- 顶栏右侧「1080P」为实测分辨率（第 8.9 节）；「画质✦」打开超分侧栏，开启超分时图标为主题色。
- 「选集」仅当同一页面检测到多个视频（或页面有剧集列表并能识别）时出现，P2。
- 竖屏播放：画面置顶 16:9，下方显示标题、站点、本页其他视频列表；点全屏或横置手机进入横屏。竖屏视频（高 > 宽）自动以竖屏全屏播放。
- 屏幕方向：默认跟随视频宽高比自动锁定；⤢ 按钮手动切换；锁屏时锁定方向。
- 刘海/挖孔：横屏时控制层避开 `displayCutout` 安全区，画面本身可延伸到挖孔区域（设置项，P2）。

### 8.3 手势（与 Ringo-Cove 保持一致）

画面区域划分：左 38% / 中 24% / 右 38%（双击判定）；左 50% / 右 50%（上下滑动判定）。

| 手势 | 行为 | 细节 |
|---|---|---|
| 单击 | 显示/隐藏控制层 | 等待 **260 ms** 确认不是双击后才执行 |
| 双击左区 | 后退 N 秒（默认 10） | 两次点击间隔 < 280 ms 且距离 < 80 dp 判为双击；**连击**：进入快进状态后 750 ms 内同侧每次单击都继续累加，无需再双击 |
| 双击右区 | 前进 N 秒 | 同上 |
| 双击中区 | 播放/暂停 | 可在设置关闭（关闭后中区双击无效果） |
| **长按**（任意位置，≥ 系统长按时长约 400 ms） | **临时倍速**（默认 2.0x），松手恢复原速度 | 仅在播放中生效；暂停时长按无效。长按期间：**左右滑动切换倍速档**（每 40 dp 一档：1.5 / 2.0 / 2.5 / 3.0），胶囊文字实时更新（P1）。长按中禁止横滑拖动进度与上下调节。音调保持（`PlaybackParameters(speed, pitch = 1f)`） |
| 横向拖动 | 拖动进度 | 整屏宽度对应「横向滑动范围」（默认 120 秒），**松手才 seek**；拖动中显示中央时间卡片；起始 10 dp 位移内判定方向（横/竖取较大分量） |
| 左侧上下滑 | 调亮度（仅当前窗口 `WindowManager.LayoutParams.screenBrightness`） | 滑动 75% 屏高 = 0→100% |
| 右侧上下滑 | 调音量（`AudioManager.STREAM_MUSIC`） | 同上；到 0 时图标变静音 |
| 双指捏合 | 画面缩放 1.0–3.0x，可双指平移；松手后若接近某个比例档（适应/填充）则吸附（P1） | |
| 拖动进度条 | 实时预览，松手 seek | 见第 8.4 节 |
| 系统返回 | 全屏 → 退出全屏；否则退出播放器回到网页（满足条件时进入画中画，见 8.7） | |

**倍速设置的安全处理**：设置速度失败（解码器不支持）时回退到原速度，并 Toast 提示（移植 Ringo-Cove `lib/core/playback_speed.dart` 的逻辑）。所有倍速变更串行排队，后发起的请求覆盖未执行的请求（参考 `player_page.dart` 的 `_queueSpeed` / `_speedRevision`）。

**超分自我保护与倍速的联动**：倍速 > 1.5 时暂停超分（直接显示），恢复 ≤ 1.5 时自动恢复。

### 8.4 进度条

- 高 4 dp（按下 8 dp），位于底栏上方，左右边距 16。
- 三层：底轨 `playerTrack` → **磁盘已缓存区段**（从 `SimpleCache.getCachedSpans` 计算，可能不连续，P1）→ 内存已缓冲（`bufferedPosition`）→ 已播放（`primary`）。
- 拖动：thumb 16 dp 白色圆点；上方气泡显示目标时间；**缩略图预览**（P1）：
  - 方案：在拖动时用第二个轻量 `MediaMetadataRetriever`（仅直链/本地文件）或对 HLS 取对应分片解码一帧（ExoPlayer 实例池 1 个，`setVideoSurface` 到 ImageReader），结果按 10 秒粒度缓存在内存 LRU（最多 60 张）。
  - 取不到缩略图时只显示时间气泡，不显示空框。
- 点击进度条任意位置：直接 seek 到该位置。
- 直播流（无 duration）：进度条换成「直播」红点标签 + 「回到直播」按钮。

### 8.5 缓存

**8.5.1 边播边存（磁盘缓存）—— P0**

- Media3 `SimpleCache` + `LeastRecentlyUsedCacheEvictor(上限)`，目录 `cacheDir/media`，上限默认 1 GB（设置可调）。
- 数据源链：`CacheDataSource(cache, upstream = OkHttpDataSource)`，`FLAG_IGNORE_CACHE_ON_ERROR`。
- **缓存键**：默认用完整 URL。另提供 `CacheKeyFactory`：对**分片**请求（`.ts` `.m4s` `.mp4` 分段、带 Range 的直链），若 URL query 中只包含常见时效签名参数（`sign`、`token`、`expires`、`timestamp`、`t`、`auth_key`、`Expires`、`Signature`、`Key-Pair-Id`、`Policy`），则去掉这些参数作为缓存键，使地址刷新后仍能命中缓存。清单文件（m3u8/mpd）**永不缓存**。
- 效果：同一视频回拖、重看不重复下载；进度条显示已缓存区段。
- 无痕模式不使用磁盘缓存。
- 设置页显示已用空间与「清除播放缓存」。

**8.5.2 预缓冲 —— P0**

```text
DefaultLoadControl
  minBufferMs               = 预缓冲时长设置（默认 60 000）的一半，最少 15 000
  maxBufferMs               = 预缓冲时长设置（默认 60 000）
  bufferForPlaybackMs       = 1 000     （起播更快）
  bufferForPlaybackAfterRebufferMs = 2 500
  backBufferDurationMs      = 30 000    （小幅回退不重新请求）
  retainBackBufferFromKeyframe = true
```

省流模式（系统 Data Saver 开启且为计费网络时）：maxBuffer 降为 30 秒。

**8.5.3 离线缓存（下载视频）—— P1**

- 使用 Media3 `DownloadManager` + `DownloadService`（HLS 用 `HlsDownloader`，可选择清晰度：读取主清单中的 variant，弹出「选择清晰度」面板）。
- 离线视频与边播边存使用**不同的 Cache 实例**（离线不会被 LRU 淘汰），目录 `filesDir/offline`。
- 在下载页「视频」分类中显示，进度、暂停、继续、删除与普通下载一致。
- 播放离线视频时完全不需要网络。
- 「导出为 MP4 文件」（保存到相册/下载目录，P2）：用 Media3 Transformer 从离线缓存重新封装（不重新编码）。**实现前先验证 Transformer 对 HLS 离线源的支持**；不支持时对 TS 分片做「拼接 + 用 Media3 Muxer 重新封装」。
- 直链 MP4 不走 Media3 下载，直接走普通下载引擎（第 10 节），完成后同样可在播放器打开。

### 8.6 解码

- 设置项：自动 / 硬件 / 软件。移植 Ringo-Cove `packages/video_player_android/.../VideoDecoderPolicy.java`：通过 `MediaCodecSelector` 过滤硬件或软件解码器。
- 播放中切换解码方式：记录当前位置 → 重建播放器 → seek 回原位置 → Toast「已切换至软件解码」。
- 硬解失败（`MediaCodecRenderer.DecoderInitializationException`）时自动尝试软解一次，并 Toast 提示。
- 可选 Media3 FFmpeg 音频扩展（`decoder_ffmpeg`）以支持 AC3/EAC3/DTS 等音频（P2，需要自行编译）。

### 8.7 其他播放能力

| 功能 | 优先级 | 说明 |
|---|---|---|
| 断点续播 | P0 | 按「页面 URL + 媒体 URL（去 query）」记录进度，每 10 秒及退出时保存；> 5 秒且距结尾 > 15 秒才续播，起播时 Toast「已从 12:34 继续播放 · 从头播放」（Toast 内有按钮） |
| 画中画 | P0 | 播放中按 Home / 返回（设置开启时）自动进入；`setAutoEnterEnabled(true)`（API 31+）；PiP 内提供 后退 10 秒 / 播放暂停 / 前进 10 秒 三个 RemoteAction |
| 后台播放 | P1 | `MediaSessionService` + 媒体通知；设置开启时退到后台继续播放（仅声音） |
| 媒体会话 | P0 | 耳机按键、蓝牙控制、锁屏控制、音频焦点（来电暂停、其他 App 播放时暂停） |
| 画面比例 | P0 | 适应 / 填充裁剪 / 拉伸 / 16:9 / 4:3（P1） |
| 倍速面板 | P0 | 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0 / 2.5 / 3.0，竖向列表，当前项主题色 |
| 跳过片头片尾 | P1 | 按**站点域名**记忆片头/片尾秒数（±5 秒步进），「设为当前位置」按钮；Ringo-Cove 是按影片记忆，这里改为按站点 + 按页面路径前缀 |
| 字幕 | P1 | 显示 HLS/DASH 内嵌字幕轨，加载本地 SRT/VTT/ASS（ASS 简化为纯文本样式）；字号、底边距、背景透明度可调 |
| 音轨选择 | P1 | 多音轨时在设置面板中选择 |
| 截图 | P2 | 从 GL 输出读取当前帧（含超分效果），保存到相册 |
| 播放列表 / 下一集 | P2 | 同页检测到多个视频时，结束后自动播放下一个 |
| 投屏 | P2 | DLNA（Cling 或自实现 SSDP） |

### 8.8 超分辨率

**全部移植 Ringo-Cove**（第 13 节列出文件），产品行为保持一致：

- **模式**：关闭（默认）/ 自动 / 动画线条（Anime4K v4）/ 通用（FSR 1.0 EASU + RCAS）。
  - 自动：判断为动画用 Anime4K，否则用 FSR；**片源分辨率已 ≥ 输出尺寸的 90% 时不处理**。
  - 「是否动画」的判断（浏览器场景无影片元数据）：页面标题/URL 包含动画关键词（番剧、动漫、anime、アニメ、bangumi、/anime/），或域名在内置动画站点列表中；否则视为真人。P2：抽帧统计平坦色块占比做分类。
- **档位**：流畅 / 均衡 / 画质（S / M / L 网络）。
- 渲染链：解码输出到 `SurfaceTexture`（OES 纹理）→ 复制到 RGBA 纹理 → 按着色器链在半精度浮点 FBO 上逐 pass 处理 → Catmull-Rom 双三次缩放到屏幕尺寸（缩小较多时先降采样）→ 输出到 `SurfaceView`。
- **入口**：顶栏「画质✦」打开右侧面板：
  ```text
  画质
  片源    1920×1080 · H.265 · 4.2 Mbps · 23.976 fps
  输出    2560×1440（超分 2.0x）
  耗时    6.8 ms / 帧（41.7 ms 预算）  ← 实时刷新，300 ms 一次
  ———
  超分辨率   [关闭] [自动] [动画] [通用]
  档位       [流畅] [均衡] [画质]
  [ 对比原画 ]   ← 开启后画面出现可拖动分割线
  ```
- **测试本机性能**（设置页）：用内置 3 秒 720P 合成片段（复用 Ringo-Cove `assets/superres/`）依次测试三档，显示每档平均耗时，推荐每帧 < 16 ms 的最高档，一键应用。
- **自我保护**：倍速 > 1.5 暂停超分；HDR（PQ/HLG）片源不开启；连续 30 帧 GPU 耗时 > 帧间隔 85% 则降一档，并记住本机可稳定运行的最高档位；`PowerManager.getCurrentThermalStatus()` ≥ SEVERE 降档、≥ CRITICAL 关闭；渲染出错回到直接显示，**不中断播放**，Toast 一次「超分已关闭：xxx」。
- 旋转、切换画面比例、进出画中画时重新计算输出尺寸；画中画中暂停超分（节能）。

### 8.9 清晰度探测

移植 Ringo-Cove `lib/core/resolution.dart` 的思路（Kotlin 重写）：

- 只读流头部，不下载整个视频：HLS 主清单 `RESOLUTION` 属性 → TS 分片中 H.264/H.265 SPS → MP4 `stsd` 视频样本描述。支持 AES-128 加密分片、伪装图片头的分片。
- 标注：≥ 2160 → 4K，≥ 1440 → 2K，≥ 1080 → 1080P，≥ 720 → 720P，其他 → 480P。
- 并发上限：全局 4，每个域名 2；视频面板关闭时取消排队任务。
- 结果缓存 30 天（键：媒体 URL 去 query），失败缓存 6 小时。播放时测到更高分辨率则更新缓存。
- 用于：视频面板列表、播放器顶栏、离线下载清晰度选择。

### 8.10 起播与失败处理

```text
起播超时 20 秒 → 提示「加载超时」+ [重试] [返回网页]
HTTP 403/401   → 用最新的请求头重试一次（让 miru-bridge 重新抓取该页面的最新 Cookie）→ 仍失败则提示「该视频需要在网页中播放」并返回网页
清单解析失败    → 尝试按直链播放 → 失败则返回网页
网络断开        → 暂停在缓冲状态，网络恢复后自动继续（ConnectivityManager 回调）
```

---

## 9. 广告拦截规格

| 项 | 规格 |
|---|---|
| 引擎 | uBlock Origin（GeckoView WebExtension），见第 2.3 节 |
| 默认状态 | 首次启动即开启，无需任何用户操作 |
| 总开关 | 设置 → 广告拦截。关闭时调用 `WebExtensionController.disable(uBO)`，重新开启时 `enable` |
| 本站开关 | 第 5.7 节盾牌面板 |
| 计数 | 页面级：uBO badge；全局累计：本地 DataStore 计数，按页面加载结束时的 badge 值累加 |
| 规则更新 | uBO 自行管理（默认每 4–5 天自动更新）；不额外干预 |
| 无痕模式 | uBO 在无痕标签中同样启用 |
| 视频广告 | 由 uBO 规则处理；**嗅探层额外忽略**：被识别为 VAST/VMAP（`application/xml` 且内容含 `<VAST`）的地址、常见广告 CDN 域名列表（内置一份小黑名单，可随版本更新） |
| 元素拾取 | 盾牌面板 →「屏蔽页面元素」→ 触发 uBO 的 element picker（通过 uBO popup 中的对应按钮或 `browser.tabs` 消息；实现者验证可行路径） |
| 首屏性能 | uBO 启动前的请求不得漏拦：GeckoView 冷启动恢复标签时，**等待扩展就绪事件后再加载页面**（最多等 1.5 秒，超时直接加载） |

---

## 10. 下载规格

### 10.1 引擎

两套引擎，统一展示在一个下载页：

```text
普通文件   自研 DownloadEngine（OkHttp），由 Android Components 的 DownloadMiddleware 截获 Gecko 的下载请求后转交
视频离线   Media3 DownloadManager（HLS/DASH），见 8.5.3
```

普通文件下载要求：

- 服务端支持 Range 且文件 > 4 MB 时，**多线程分段下载**（线程数默认 4，设置 1–8），每段独立重试 3 次（指数退避 1s/2s/4s）。
- 断点续传：记录每段进度到 Room；进程被杀、重启手机后可继续（ETag / Last-Modified 校验，变化则从头下载）。
- 网络切换：Wi-Fi ↔ 移动数据时不中断；断网时进入「等待网络」状态，恢复后自动继续。
- 同时进行的下载数默认 3。
- 前台服务通知：每个下载一条进度通知（合并为分组通知），可在通知中暂停/取消；完成通知点击打开文件。
- 保存位置：默认 `Download/Miru/`（通过 MediaStore，无需存储权限）；用户可通过 SAF 选择其他目录。
- 文件名：`Content-Disposition` → URL 路径 → 根据 MIME 推断扩展名；重名自动加 ` (1)`。
- 携带页面上下文：下载请求带上 Gecko 提供的 Cookie、Referer、User-Agent（`DownloadState` 中已有）。
- 下载 `blob:` / `data:` URL：交给 Gecko 自带下载流程处理后保存。

### 10.2 数据结构（Room）

```text
downloads
  id               TEXT PK (UUID)
  kind             TEXT   file | video
  url              TEXT
  file_name        TEXT
  mime_type        TEXT
  dest_uri         TEXT   MediaStore / SAF URI
  total_bytes      INTEGER (-1 未知)
  downloaded_bytes INTEGER
  status           TEXT   queued | running | paused | waiting_network | failed | completed | cancelled
  error            TEXT?
  referrer         TEXT?
  headers_json     TEXT   (Cookie 加密存储或仅存在内存，见 12.1)
  source_page      TEXT?
  thumbnail_path   TEXT?  视频/图片的缩略图
  created_at, completed_at INTEGER
download_segments
  download_id, index, start, end, downloaded
```

### 10.3 分类规则

```text
视频   video/*、kind=video、扩展名 mp4 mkv webm mov flv avi ts m3u8
音频   audio/*、mp3 flac aac m4a ogg wav
图片   image/*
文档   pdf doc(x) xls(x) ppt(x) txt epub md csv
压缩包 zip rar 7z tar gz bz2 xz
安装包 apk xapk apks
其他   其余
```

---

## 11. 功能总表（含验收标准）

### 11.1 浏览器核心

| # | 功能 | 优先级 | 验收标准 |
|---|---|---|---|
| B1 | 网页浏览（GeckoView） | P0 | 能正常打开 Google、YouTube、Bilibili、知乎、GitHub、淘宝移动版并登录 |
| B2 | 多标签、后台标签、会话恢复 | P0 | 杀进程后重开，所有标签（含历史栈）恢复 |
| B3 | 地址栏：搜索/网址判断、建议、内联补全 | P0 | 输入「github」出现 github.com 补全；输入中文直接搜索 |
| B4 | 新标签页（Logo + 搜索框 + 快捷方式） | P0 | 冷启动到 NTP 可交互 < 800 ms（中端机） |
| B5 | 标签切换器（网格、滑动关闭、撤销、无痕分组） | P0 | 关闭后 Snackbar 撤销可恢复标签及其历史 |
| B6 | 主菜单 | P0 | 结构与第 5.6 节一致 |
| B7 | 历史记录 | P0 | 搜索、按日期分组、删除、清除 |
| B8 | 书签（星标、列表） | P0；文件夹 P1 | |
| B9 | 无痕模式 | P0 | 关闭无痕标签后历史、Cookie、播放进度均无残留 |
| B10 | 下拉刷新、滚动隐藏工具栏 | P0 | |
| B11 | 长按上下文菜单（链接/图片/视频） | P0 | |
| B12 | 页内查找 | P0 | |
| B13 | 权限请求、站点设置 | P0 | |
| B14 | 设为默认浏览器、处理外部 Intent | P0 | |
| B15 | 地址栏位置顶部/底部 | P1 | 切换后不需要重启 |
| B16 | 横滑工具栏切换标签 | P1 | |
| B17 | 桌面版网站 | P0 | |
| B18 | 添加到主屏幕 / PWA | P1 | |
| B19 | 主题：浅/深/跟随系统、动态取色、预设主题色 | P0 | |
| B20 | 清除浏览数据 | P0 | |
| B21 | 安全 DNS、仅 HTTPS、阻止第三方 Cookie | P1 | |
| B22 | 标签分组、最近关闭的标签 | P2 | |
| B23 | 阅读模式 | P2 | 使用 Android Components `feature-readerview` |
| B24 | 翻译 | P2 | |

### 11.2 广告拦截

| # | 功能 | 优先级 | 验收标准 |
|---|---|---|---|
| A1 | 内置 uBO，首启自动安装启用 | P0 | 首启后打开 `https://d3ward.github.io/toolz/adblock.html` 得分与桌面 Firefox + uBO 基本一致 |
| A2 | 地址栏盾牌 + 本页拦截数 | P0 | |
| A3 | 盾牌面板：本站开关、元素拾取、高级设置 | P0（元素拾取 P1） | 关闭本站拦截后刷新，广告恢复显示 |
| A4 | 全局开关、例外列表 | P0 | |
| A5 | 累计拦截统计 | P1 | |
| A6 | 冷启动不漏拦 | P0 | 恢复的标签首屏无广告 |

### 11.3 视频嗅探

| # | 功能 | 优先级 | 验收标准 |
|---|---|---|---|
| V1 | miru-bridge 扩展：HLS/DASH/直链识别，带请求头 | P0 | 用本地测试页（MockWebServer 提供 m3u8 + mp4）识别率 100%，不上报分片 |
| V2 | 地址栏视频胶囊 + 视频列表面板 | P0 | |
| V3 | 页面开始播放/全屏时提示「用 Miru 播放器」 | P0 | 同一页面只提示一次 |
| V4 | 接管播放：暂停网页视频，从当前时间继续 | P0 | 误差 < 2 秒 |
| V5 | 自动接管开关 | P1 | |
| V6 | 网页内长按倍速 | P1 | |
| V7 | 清晰度/时长探测并标注 | P1 | |

### 11.4 播放器

| # | 功能 | 优先级 | 验收标准 |
|---|---|---|---|
| P1 | ExoPlayer：HLS / DASH / MP4 / WebM / FLV(P1) / 本地文件 | P0 | |
| P2 | **长按倍速**（默认 2x，可设），松手恢复 | P0 | 按住 400 ms 内生效；松手立即恢复；暂停状态无效 |
| P3 | 长按中左右滑动切换倍速档 | P1 | |
| P4 | **拖动进度条**：按下加粗、时间气泡、松手 seek | P0 | |
| P5 | 横滑屏幕拖动进度 | P0 | |
| P6 | 进度条缩略图预览 | P1 | |
| P7 | 双击快进/快退（连击累加）、双击中间暂停 | P0 | |
| P8 | 左右上下滑调亮度/音量 | P0 | |
| P9 | **边播边存磁盘缓存**（LRU，可设上限，签名参数去除） | P0 | 看完一段后断网回拖，已看部分可播放 |
| P10 | 预缓冲时长可设 | P0 | |
| P11 | 进度条显示已缓存区段 | P1 | |
| P12 | **离线缓存视频**（HLS 选清晰度，下载页管理） | P1 | 飞行模式下可完整播放 |
| P13 | **超分辨率**：Anime4K / FSR，三档，对比，统计 | P1 | 1080P 屏幕播放 540P 动画，均衡档在骁龙 8 Gen 2 级设备上每帧 < 12 ms |
| P14 | 超分性能测试与自我保护 | P1 | |
| P15 | 解码方式切换、硬解失败自动软解 | P0 | |
| P16 | 断点续播 | P0 | |
| P17 | 画中画、媒体会话、音频焦点 | P0 | |
| P18 | 后台播放 | P1 | |
| P19 | 锁屏、画面比例、倍速面板、屏幕方向 | P0 | |
| P20 | 跳过片头片尾（按站点） | P1 | |
| P21 | 字幕、音轨 | P1 | |
| P22 | 双指缩放 | P1 | |
| P23 | 截图、下一集、投屏、导出 MP4 | P2 | |

### 11.5 下载

| # | 功能 | 优先级 | 验收标准 |
|---|---|---|---|
| D1 | 内置下载页（分类、日期分组、搜索、多选） | P0 | |
| D2 | 下载确认面板（改名、改目录） | P0 | |
| D3 | 多线程分段、断点续传、自动重试、等待网络 | P0 | 下载 500 MB 文件中途杀进程，重开后继续，最终 SHA-256 正确 |
| D4 | 通知栏进度与控制 | P0 | |
| D5 | 下载动画（箭头飞入菜单、⋮ 进度点） | P1 | |
| D6 | 视频离线下载与普通下载统一管理 | P1 | |
| D7 | 存储占用条 | P1 | |

---

## 12. 数据、隐私与性能

### 12.1 数据与隐私

- **无账号、无遥测、无云同步**（Android Components 的 Glean、Sync、FxA 相关模块全部移除，`GeckoRuntimeSettings` 关闭 Telemetry/Crash Reporter 上传）。崩溃日志只保存在本机，「设置 → 关于」可导出。
- Cookie / 授权头**不写入磁盘**：下载记录中的请求头只在进程内存中保存；进程重启后续传若需要 Cookie，从 Gecko 的 Cookie 存储重新取（通过 miru-bridge 的 `cookies` API）。
- 播放记录、下载记录、设置保存在应用私有目录；「清除浏览数据」提供「播放进度」「播放缓存」两个额外选项。
- 默认搜索引擎的搜索建议请求可在设置中关闭。

### 12.2 性能指标（中端机：骁龙 7 Gen 1 级 / 8 GB）

```text
冷启动到 NTP 可交互            < 800 ms
冷启动到恢复的网页首次绘制       < 2.0 s
NTP 滚动/动画                   无掉帧（90 Hz）
标签切换器打开                  < 100 ms 开始动画（缩略图预先生成）
视频起播（网络良好，HLS）        < 1.5 s
长按倍速生效延迟                 < 100 ms（手势判定后）
内存：10 个标签                 Gecko 自动回收后台标签内容进程；我们不额外限制
APK（arm64 单 ABI）             < 110 MB（GeckoView 占大头）
```

---

## 13. 从 Ringo-Cove 复用清单

路径均相对 `G:\Projects\Ringo-Cove`。

| 内容 | 来源 | 复用方式 |
|---|---|---|
| Anime4K / FSR 着色器（mpv hook 格式 GLSL） | `packages/video_player_android/android/src/main/assets/anime4k/*.glsl` | **原样复制**到 `:feature:superres` 的 assets |
| mpv hook 着色器解析 | `packages/video_player_android/android/src/main/kotlin/io/flutter/plugins/videoplayer/superres/HookShader.kt` | 直接复制，改包名 |
| GL 超分渲染器 | 同目录 `SuperResRenderer.kt` | 复制后去掉 Flutter Texture 相关代码，输出改为 `SurfaceView` 的 Surface；保留 FBO 链、统计、错误回退 |
| 着色器链选择、输出尺寸计算、档位记忆 | `lib/core/super_res.dart`（`superResChain`、`fittedOutput`、`SuperResDeviceTier`、`SuperResStatus.overloaded`） | Kotlin 重写，逻辑一致 |
| 超分性能测试 | `lib/ui/settings/super_res_benchmark.dart` + `assets/superres/` | 测试片段复制；页面用 Compose 重写 |
| 解码器选择 | `packages/video_player_android/.../VideoDecoderPolicy.java` | 直接复制 |
| 手势判定与常量 | `lib/ui/player/player_page.dart`（`_onTapUp` 附近：38%/62% 分区、280 ms/80 dp 双击、260 ms 单击延迟、750 ms 连击窗口；`_onHDrag*`、`_onVDrag*`；`_queueSpeed`） | Compose `pointerInput` 重写，**数值保持一致** |
| 控制层组件视觉 | `lib/ui/player/player_widgets.dart`（`DoubleTapRipple`、`_SideArcClipper`、`SpeedPill`、`LevelIndicator`、`SidePanel`、`VideoProgressBar`） | Compose 重写，视觉与时长保持一致 |
| 安全倍速设置 | `lib/core/playback_speed.dart` | Kotlin 重写 |
| 清晰度探测（HLS RESOLUTION / SPS / MP4 stsd / AES-128 / 伪装图片头） | `lib/core/resolution.dart`、`lib/core/hls_probe.dart` | Kotlin 重写 |
| 播放器设置项默认值 | `lib/data/settings.dart`（`AppSettings`） | 默认值照抄：双击 10 秒、长按 2.0x、滑动范围 120 秒等 |
| iOS Metal 超分（为第 14 节准备） | `packages/video_player_avfoundation/.../SuperResProcessor.swift`、`Anime4KShaders.swift`、`scripts/gen_anime4k_metal.py` | iOS 阶段复用 |
| 第三方许可 | Ringo-Cove README「第三方组件」表 | Anime4K（MIT）、FSR（MIT）许可原文随代码保存 |

**不复用**：Ringo-Cove 的片源协议（网飞猫、Lanerc、AniCh、CMS）与媒体代理，Miru 是通用浏览器，不内置任何片源。

---

## 14. iOS 规划（Android M4 之后启动）

iOS 上所有浏览器必须使用 WebKit（欧盟 BrowserEngineKit 例外，不考虑），因此 **GeckoView 与 uBlock Origin 扩展都无法使用**。iOS 版是独立的原生工程，共享本文档的交互、主题、动效、功能定义。

| 层 | iOS 方案 |
|---|---|
| UI | SwiftUI（外壳）+ UIKit（WKWebView 容器、播放器手势） |
| 网页 | `WKWebView`，每个标签一个实例，`WKWebsiteDataStore.nonPersistent()` 用于无痕 |
| 广告拦截 | 把 uBO 使用的同一批过滤列表（uBO filters、EasyList、EasyPrivacy、AdGuard Chinese 等）转换为 `WKContentRuleList`（推荐 AdGuard `SafariConverterLib`，GPL-3.0，注意许可），每个 RuleList 不超过 15 万条规则；元素隐藏和脚本类规则用 `WKUserScript` 注入。效果弱于 uBO，文档和界面上称为「广告拦截」而非「uBlock Origin」 |
| 视频嗅探 | `WKUserScript` 在 `document_start` 注入：hook `XMLHttpRequest`/`fetch`/`HTMLMediaElement.src`/`MediaSource`，识别 m3u8/mp4，经 `WKScriptMessageHandler` 回传；Cookie 用 `WKHTTPCookieStore` 获取 |
| 播放器 | `AVPlayer` + 自绘控制层，手势与 Android 完全一致；超分复用 Ringo-Cove 的 Metal 实现 |
| 缓存 | `AVAssetResourceLoaderDelegate` + 本地分片缓存，或本地 HTTP 代理（参考 Ringo-Cove `lib/core/media_proxy.dart` 的回环代理思路）；离线用 `AVAssetDownloadURLSession` |
| 下载 | `URLSession` 后台下载 + 自建下载页 |

为了 iOS 复用，Android 实现时请：
- 把 miru-bridge 的媒体识别逻辑（URL/Content-Type 判定、去重、短视频过滤、广告 VAST 识别）写成**纯 JS 模块**，iOS 的 user script 可直接复用；
- 把设计 token 同时导出为 JSON（`design/tokens.json`），iOS 端生成 Swift 常量；
- 播放器手势参数集中在一个常量文件中，iOS 照抄数值。

---

## 15. 里程碑与测试

### 15.1 里程碑

```text
M0  骨架        fork reference-browser → 移除 Glean/Sync/FxA → Compose 外壳（地址栏、NTP、标签切换器、菜单）→ 会话恢复
M1  拦截与下载   uBO 内置安装、盾牌与面板、下载引擎与下载页、下载动画
M2  播放器核心   miru-bridge 嗅探、视频面板、PlayerActivity、全部 P0 手势、边播边存缓存、断点续播、画中画
M3  画质与离线   超分移植、性能测试、清晰度探测、离线视频下载、缩略图预览、字幕
M4  打磨        全部动效按第 7 节核对、设置页补齐、无障碍、性能指标达标、发布构建
M5  iOS         见第 14 节
```

每个里程碑结束时：所有该阶段 P0 项通过第 11 节验收标准，录制一段操作视频（`adb shell screenrecord`）。

### 15.2 测试

```text
单元测试（JVM）
  · 媒体识别：URL / Content-Type 判定、去重、短视频与 VAST 过滤（同一份用例供 JS 模块测试）
  · 缓存键：签名参数去除规则
  · 下载引擎：分段计算、Range 拼接、断点恢复、ETag 变化、重名
  · 清晰度探测：SPS 解析、HLS 主清单、MP4 stsd（复用 Ringo-Cove 的测试样本思路）
  · 手势状态机：单击/双击/连击/长按/拖动的判定时序（用虚拟时钟）
  · 超分：着色器链选择、输出尺寸、降档逻辑
仪器测试（模拟器 + 真机）
  · Compose UI 测试：地址栏、NTP、标签切换器、下载页、设置
  · Gecko 集成：MockWebServer 提供测试站点（含广告请求、m3u8、mp4、带 Referer 校验的媒体）
      - uBO 拦截了测试广告域名
      - 嗅探面板出现预期的视频
      - 接管播放成功且时间衔接
  · 播放器：本地 HLS 夹具起播、长按倍速、拖动、缓存命中（第二次播放无网络请求）、离线播放
  · 超分：三档运行、对比分割、出错回退不断播
手动验收（真机）
  · 第 11 节每条验收标准
  · 深浅色 × 地址栏顶/底 × 系统字体 1.3x 截图核对
  · 「移除动画」开启后全流程可用
无障碍
  · TalkBack 可完成：打开网页、切换标签、下载、播放/暂停、调整倍速
  · 所有图标按钮有 contentDescription；播放器手势均有等价按钮
```

---

## 16. 风险与待验证项

实现者在对应里程碑开始前先做最小验证，结论写回本文档。

| # | 风险 | 验证方法 / 备选 |
|---|---|---|
| R1 | `reference-browser` 仓库可能已不活跃，或 Android Components 已并入 mozilla-central | 若仓库过时，直接依赖 `maven.mozilla.org` 上与 GeckoView 同版本的 Android Components 包，参照 mozilla-central 中 `mobile/android/android-components/samples/browser` 搭骨架 |
| R2 | uBO 用 `install(file://...)` 安装是否需要网络/签名校验通过；社区报告过用 `ensureBuiltIn` 安装 uBO 的权限报错 | M1 第一天验证；备选：首启从 AMO 在线安装（`https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/`），失败时提示重试 |
| R3 | 盾牌面板嵌入 uBO popup、元素拾取器的调用路径 | 参考 Fenix 中 uBO 的 popup 展示方式；不行则降级为「在新标签打开 uBO 面板」 |
| R4 | `webRequest` 是否能拿到全部媒体请求（Service Worker 发起的请求、跨域 iframe） | 测试 YouTube（预期拿不到可直接播放的地址，属正常，YouTube 不支持接管）、B 站、常见 HLS 站点；拿不到时只提供网页内长按倍速 |
| R5 | 接管后服务端校验 Cookie / Referer / 时效签名导致 403 | 8.10 的重试策略；失败则回网页 |
| R6 | Media3 Transformer 对 HLS 离线源导出 MP4 的支持 | P2 前验证 |
| R7 | SuperResRenderer 从 Flutter Texture 改为 SurfaceView 输出后的帧同步与 HDR 处理 | M3 初期用 Ringo-Cove 的测试片段对比截图 |
| R8 | GeckoView 每个 ABI 体积大，APK 超标 | 必须按 ABI 拆分；考虑 `geckoview-omni` vs `geckoview` 包的差异，选最小可用包 |
| R9 | uBO（GPL-3.0）与 GeckoView（MPL-2.0）随 APK 分发的许可合规 | 发布前人工确认；在「关于」中提供全部开源许可与源码链接 |
| R10 | Google Play 政策对「下载网页视频」功能的审核风险 | 若上架 Play，视频下载功能可能需要对特定站点（如 YouTube）禁用；侧载/其他商店分发不受影响 |

---

### 实现验证记录（2026-10-22）

- **R1 / M0**：已核对仍在维护的 `mozilla-mobile/reference-browser`，参考提交 `61267efe132591f6f1dfd58185ebd8dbff6aa816`。Miru 采用其 GeckoRuntime / GeckoEngine / BrowserStore / EngineMiddleware / SessionStorage 装配方式，Compose 外壳独立实现；没有引入上游账号、同步及应用层遥测初始化。当前固定稳定版 Android Components `158.0.2`，由其传递依赖配套 GeckoView，不混用 nightly。源码和许可记录见 `docs/UPSTREAM.md`。
- **工具链**：AGP `9.1.1`、Gradle `9.3.1`、Kotlin `2.4.20`、JDK 21、compile/target SDK 37。官方 SDK 包名为 `platforms;android-37.0`。
- **Gecko 多进程**：Application 必须只在主进程创建 AppContainer。Gecko helper/content 进程也会调用 Application.onCreate；重复初始化运行时会导致进程退出。引擎初始化安排在原生首帧之后，Gecko 创建在主线程，会话 I/O 在后台线程。
- **验收边界**：当前为 M0 开发预览。构建与测试记录见 `docs/M0-VALIDATION.md`，未完成的功能与验收见 `docs/PROGRESS.md`。R2/R3 的官方签名 uBO 安装及控制面板验证留在 M1，不将 Gecko 跟踪保护标为 uBO 广告拦截。

## 附录 A：默认设置值一览

```text
search.engine                 google
appearance.theme              system
appearance.dynamicColor       false
appearance.accent             ringoRed
appearance.toolbarPosition    top
privacy.blockThirdPartyCookies true
privacy.httpsOnly             false
adblock.enabled               true
media.autoTakeover            false
media.webLongPressSpeed       true
player.doubleTapSeek          10
player.longPressSpeed         2.0
player.defaultSpeed           1.0
player.swipeSeekRange         120
player.gestureBrightness      true
player.gestureVolume          true
player.doubleTapCenterPause   true
player.haptics                true
player.resume                 true
player.backgroundPlay         false
player.autoPip                true
player.videoFit               contain
player.decoder                auto
player.superRes               off
player.superResTier           balanced
cache.streamLimitMb           1024
cache.prebufferSec            60
download.askBeforeDownload    true
download.maxConcurrent        3
download.threadsPerFile       4
download.videoWifiOnly        false
download.deleteFileWithRecord true
```

## 附录 B：文案风格

- 中文为主，语气平实、简短，不用感叹号，不用「亲」「哦」。
- 按钮用动词：「下载」「重试」「打开」「撤销」。
- 错误提示说清楚发生了什么和怎么办：「下载失败：存储空间不足。清理空间后点重试。」
- 数字与单位之间留空格：`12 MB`、`2.0x`（倍速不留空格）、`10 秒`。
- 所有用户可见字符串放在 `strings.xml`，预留英文翻译（`values-en`）。
