# 全量验收矩阵

审计日期：2026-10-23（Asia/Shanghai）。需求来源为 [MIRU-SPEC.md](../MIRU-SPEC.md) 全文，包含第 11 节、其他章节的细节与附录，不仅是已经实现的功能。

原始审计基线为 **42daa92 / 0.4.0**；0.4.1 离线新增证据见 E4，后续超分见 E5，并只更新被实际覆盖的条目。不能把开发中的文件、旧版本测试或绿色构建等同于最终验收。M1 首装仍按专项记录验证，完整阶段均未宣称完成。

状态定义：

- `verified`：该行所列的具体要求已有相称的直接证据，证据范围在表中明确；不会自动提升为整个里程碑完成。
- `implemented`：已有部分或全部代码，但验收仍有缺项、差异或未验证平台。
- `pending`：尚未实现、缺少足够证据，或已有结果明确不达标。括号说明失败 / 依赖条件。

P0 是首个可用版本门，P1 是第二轮功能，P2 在规范中为“有余力再做”。P2 仍逐项保留，不会把“未做”写成“已验收”；如果维护者决定不纳入某次发行，需明确记录发行范围，而不能删去原需求。iOS 也是规范内的 M5，顺序为 Android M4 之后，不从目标中静默排除。

## 证据索引与使用边界

| 代号 | 证据 | 可以证明的范围 |
| --- | --- | --- |
| E0 | [M0-VALIDATION.md](M0-VALIDATION.md)；`artifacts/m0/` | API 35 本地网页 / 会话恢复 / UI 烟测、历史书签；部分手工截图和 `miru-m0.mp4`。不证明真机性能 / 全站登录。 |
| E1 | [M1-VALIDATION.md](M1-VALIDATION.md)、[M1-ADBLOCK.md](M1-ADBLOCK.md)、[M1-DOWNLOAD-REVIEW.md](M1-DOWNLOAD-REVIEW.md) | 普通下载、500,000,000 字节 force-stop 续传与 SHA、本地广告 fixture；同时记录 uBO 首装超时。不是手机重启或 d3ward 对比。 |
| E2 | [M2-VALIDATION.md](M2-VALIDATION.md)、[M2-031-VALIDATION.md](M2-031-VALIDATION.md) | 本地 MP4 / HLS / DASH、接管时间、缓存、进度、手势部分、认证 / 生命周期和网页交互专项。 |
| E3 | [M3-VALIDATION.md](M3-VALIDATION.md)；`artifacts/m3/delivery-device.log` / `delivery-build.log` | 基线常规设备 **38/38**（137.221 s）、JVM **42/42**、Debug / ARM64 release 构建和 Lint。已读取最终日志成功行；设备为 x86_64 API 35。 |
| E3-P | `M3ProbeIntegrationTest`、`M3MediaPanelIntegrationTest`、`ResolutionProbeTest` | 受控 HTTP 探测、范围读取 / 缓存 / 取消 / 认证隔离、H.264 / H.265 解析、真实 Gecko 面板显示。 |
| E3-T | `M3TracksIntegrationTest`、`LocalSubtitlesTest`；`artifacts/m3/m3-*.png` | 实际 HLS 音轨 / 字幕切换、内存导入 SRT / VTT / ASS、按时间显示、解码重建、样式。没有完整系统 SAF 点击验收。 |
| E4 | [M3-OFFLINE-VALIDATION.md](M3-OFFLINE-VALIDATION.md)、`artifacts/m3-offline/` | API35离线 HLS / DASH 完整停源播放、清晰度 / 音轨 / 字幕、缓存分区 / 取消竞态、真实私密下载入口和统一列表；47项常规 / 54项JVM。保留并行录屏轮9项失败，不能代替真机 / 录屏兼容矩阵。 |
| E5 | [M3-SUPERRES-VALIDATION.md](M3-SUPERRES-VALIDATION.md)、`artifacts/m3-quality/commit-superres-device.log`；1432d83 | 超分10项JVM、API35三项真实GL专项通过（98.099秒），含60帧/故障回退/暂停画面与旋转。不是物理GPU性能成绩或完整播放器回归。 |
| E6 | [OFFLINE-NOTIFICATION-VALIDATION.md](OFFLINE-NOTIFICATION-VALIDATION.md)、`artifacts/m3-quality/notification-cleanup-device.log`；35ad373 | 2项socket JVM、3项API35真实通知专项通过（27.387秒），含挂读暂停、完成UUID停源全片播放、哈希碰撞隔离。不是厂商后台或权限拒绝矩阵。 |
| E7 | [M1-BLOCKLIST-VALIDATION.md](M1-BLOCKLIST-VALIDATION.md)、`artifacts/m1-blocklist/` | 固定官方blocklist资源、36项归档检查、最终APK原4079项与5项新增的逐项完整性；API35 fresh-profile离线/在线各1项签名安装与受控拦截通过。未证明多轮真机稳定性、MLBF读取追踪或在线更新。 |
| E8 | [BROWSER-CORE-VALIDATION.md](BROWSER-CORE-VALIDATION.md)、`artifacts/browser-core/` | 真实Cookie时间边界/私密表单、APZ/工具栏、HTTP/meta/frame来源策略、内存图片URI。0.5.1常规75/77；0.5.2为77/78。0.5.3：107项JVM、46项JS、14项设备专项、同值设置及fresh首装通过；全套79项77通过2失败（混合下载撤销、QUALITY benchmark），四条60帧GL通过。107,897,008字节发布打包已验证，不能扩大为全部B10/B11/B20或后续B13验收。 |
| E9 | [B13-PERMISSIONS-VALIDATION.md](B13-PERMISSIONS-VALIDATION.md)、`artifacts/browser-core/054-*` | 0.5.4首批：固定Observer默认reject根因与只读回调状态前后对照；neutral专项2/2（21.221秒）、无trace站点/混合下载7/7（61.002秒），含实际live音视频及临时GEO新文档再问。保留media-details5/7及fresh5588失败；早拒绝/归属修复后fresh5590真实系统专项3/3（21.741秒），无预授权，实际拒绝/摄像头麦克风允许/网站BLOCK/仅近似定位通过；最终production源码非trace站点复跑7/7（46.626秒）。overlay已提交f3117fb，不代表应用权限批次或完整B13验收。 |
| SRC | 当前模块源码、Gradle、manifest、资源和 `design/tokens.json` | 证明存在代码 / 配置。单独不能证明运行正确、无泄漏、无掉帧或兼容所有站点。 |
| LEGAL | [OPEN-SOURCE-REVIEW.md](OPEN-SOURCE-REVIEW.md)、[third_party/README.md](../third_party/README.md) | M0 源码公开范围的检查和后续依赖说明；不是含 uBO / 嵌套依赖的完整二进制分发审查。 |

E3 常规设备套件明确排除 `AdblockIntegrationTest`（首装偶发 240 秒超时）与 `DownloadRestartTest`（需要分阶段外部 force-stop）。排除的项目仍未通过，不能以 38/38 抵消它们。历史验证结果须按版本保留，最终交付还要重跑相应范围。

## 第 11 节功能 ID

### 浏览器 B1–B24

| ID / 优先级 | 状态 | 已有实现与证据 | 仍需完成 / 证明 |
| --- | --- | --- | --- |
| B1 P0 网页浏览 | implemented | `BrowserController` / GeckoView；E0、E3 的真实本地导航。 | Google、YouTube、Bilibili、知乎、GitHub、淘宝移动版正常浏览和登录；真实网站媒体 / 权限 / 文件交互。 |
| B2 P0 多标签 / 后台 / 恢复 | implemented | BrowserStore 单一状态源、SessionStorage；E0 force-stop 后页二恢复并可回页一。 | 当前交付完整多标签与历史栈杀进程回归、后台标签 / 低内存 / 设备重启矩阵。 |
| B3 P0 地址栏 | implemented | URL / 中文搜索、历史 / 书签 / 已开标签建议，AddressResolverTest。 | 在线 suggest、内联补全（github→github.com）、查询保持、自定义模板及隐私开关。 |
| B4 P0 NTP | implemented | 原生 Logo / 同输入状态 / 快捷方式，E0 / BrowserUiTest。 | 指定中端真机冷启动可交互 <800 ms；访问频率排序与完整 §5.2 行为。 |
| B5 P0 标签切换器 | implemented | 2 列、当前描边、普通 / 无痕、缩略图、关闭撤销；E0 / E3。 | 40% / 1000 dp/s 阈值和当前速度 spring、关闭后导航栈撤销、关全组完整恢复、容器变换。 |
| B6 P0 主菜单 | implemented | `MiruMenu` 260 dp、导航 / 书签 / 下载 / 分享 / 查找 / 桌面站点 / 设置。 | §5.6 全部适用项；添加主屏入口目前不可用，P2 最近标签未做；动效 / 状态逐项核对。 |
| B7 P0 历史 | verified | `BrowsingLibraryTest` / BrowserUiTest / E0 / E3 覆盖搜索、日期分组、删 / 清与撤销。 | 此行仅核心 CRUD；favicon、清 Cookie / 缓存入口和 Places 偏差另列。 |
| B8 P0 书签；文件夹 P1 | implemented | 星标、平铺列表、编辑 / 删 / 撤销及建议；E0 / E3。 | 文件夹树和默认“移动设备书签”；星标动画、favicon、Places 偏差。 |
| B9 P0 无痕 | implemented | 私密标签不恢复、FLAG_SECURE、不写历史 / 播放进度 / 流缓存、Cookie jar 分离，E0–E3。 | 关最后无痕标签后 Cookie / 表单 / 内存私密状态完整清理审计、常驻关闭通知、所有返回 / 下载路径。 |
| B10 P0 下拉刷新 / 隐藏栏 | implemented | E8：真实Gecko compositor滚动、APZ允许接管、SwipeRefreshFeature+M3指示、DynamicToolbar负裁剪；顶底栏滚动/页顶刷新通过。 | 网站既有手势/嵌套滚动、固定底部元素、键盘/焦点/旋转/字体完整真机矩阵。 |
| B11 P0 上下文菜单 | implemented | E8：真实Engine hit链接/图片/视频菜单；链接文字、HTTP图片原字节/私密敏感剪贴板、新无痕标签、SVG只读URI通过。 | blob原文档图片完整动作、实际跨应用分享、视频原生播放/下载及各项菜单入口全部专项；站点已有手势兼容。 |
| B12 P0 页内查找 | verified | `BrowserUiTest` 通过真实 Gecko 查找；聚焦键盘、计数 / 上下 / 关闭已有。 | 保留全站 / 长页回归。 |
| B13 P0 权限 / 站点设置 | pending | E9：已接入三项权限面板、普通/私密选择与B20联动；旁听Observer修复后真实音视频live、临时GEO新文档再问。Media早拒绝/原pending归属等最终production源码非trace站点/混合下载7/7（46.626秒），fresh5590真实系统专项3/3（21.741秒），含拒绝、各自允许、网站BLOCK及仅近似定位；应用权限批次尚未整批提交/验收。 | 系统永久拒绝、旋转/取消/迟到结果与完整生命周期/持久化/私密隔离、活动流停止仍需证据。通知、弹窗、自动播放、文件/页面提示、证书与站点数据管理仍未闭合。 |
| B14 P0 默认浏览器 / Intent | implemented | RoleManager、http(s)、SEND 文字 / URL、WEB_SEARCH；播放器独立 VIEW filter。 | 首启可跳过引导、系统默认浏览器实际选择、外部返回来源 App、协议拉起确认及文件类型矩阵。 |
| B15 P1 地址栏顶 / 底 | implemented | DataStore 即时生效、键盘避让，E0 截图。 | 完整无重启 / 主题 / 字体 / 旋转 / 表单设备矩阵和二选一卡片示意 UI。 |
| B16 P1 横滑工具栏 | pending | 无接入。 | 相邻标签截图跟手、阈值、速度 spring 与无标签边界。 |
| B17 P0 桌面版 | implemented | 切 UA / reload、菜单复选框。 | 受控 UA 断言和实际站点 / 恢复行为验收。 |
| B18 P1 主屏幕 / PWA | pending | 菜单枚举存在，动作未接入。 | AC PWA、系统添加和安装后的启动 / icon / scope。 |
| B19 P0 主题 | implemented | 系统 / 浅 / 深、动态取色、品牌色与无痕色；E0 / E2 系统栏。 | 六预设 UI / 持久化、深浅×顶底×字体 1.3x、颜色 token / 图标审查。 |
| B20 P0 清浏览数据 | implemented | E8：真实设置勾选/时间范围/确认/逐项结果；普通与私密Cookie、localStorage、表单导航、历史与播放进度直接删除/保留断言。网页/播放缓存、站点权限、HTTP认证已有API接线。 | 缓存/权限/认证各类真实删除、表单清理后强停恢复、书签/下载/离线保留完整取证。Gecko不支持的时间筛选明确禁用，不能视为API已有该能力。 |
| B21 P1 DNS / HTTPS / 三方 Cookie | pending | Gecko 推荐 tracking protection 不等同这些用户设置。 | DoH 自动 / 关 / 自定义，HTTPS-only，阻第三方 Cookie 默认开与实际请求验证。 |
| B22 P2 分组 / 最近关闭 | pending | 普通 / 无痕分组不等同自定义标签组；只有短期撤销。 | 分组与最近关闭页面 / 列表恢复。 |
| B23 P2 阅读模式 | pending | 未接 `feature-readerview`。 | 入口、提取、主题 / 字体与返回原页。 |
| B24 P2 翻译 | pending | 未实现。 | 明确服务 / 隐私、交互与可访问性后实现并测。 |

### 广告 A1–A6

| ID / 优先级 | 状态 | 已有实现与证据 | 仍需完成 / 证明 |
| --- | --- | --- | --- |
| A1 P0 官方 uBO 首启 | implemented | 固定1.75.0、SHA、签名XPI、file install/private授权；E1。E7新增官方blocklist资源及全新资料离线/在线各1轮真实拦截通过。 | 历史超时不能据两轮抵消；全新profile多轮/真机/更新路径、d3ward与桌面Firefox同版uBO同规则比较。 |
| A2 P0 盾牌 / 本页计数 | implemented | 实际 browserAction badge、未就绪 / `!` 刷新警告；本地 fixture 计数 >0。 | 零 / 变化数字、SPA / 导航 / 私密、UI / 准确性矩阵。 |
| A3 P0 站点 / 高级；元素拾取 P1 | implemented | 原版 popup-fenix / dashboard，站点白名单来源在 uBO。 | 关闭本站→刷新广告恢复专项、完整 Compose 外框 / 单一网站开关、元素拾取实际路径。 |
| A4 P0 全局 / 例外 | implemented | enable / disable / 原版 dashboard 例外。 | 可删除原生例外列表与切换 / 重启 / 私密回归。 |
| A5 P1 累计统计 | pending | 页面 badge 已有；没有累计总数 / 节省流量估算。 | 持久统计去重、页面累加口径、详情记录 logger 入口。 |
| A6 P0 冷启动不漏 | implemented | 真实 onReady / 最长 1.5 s gate；已装 uBO 的受控恢复页首广告没到 server，E1。 | 首装稳定性、超时放行分支、跨站 / 真机恢复矩阵；1.5s 超时允许加载不等同无条件零漏拦。 |

### 视频嗅探 V1–V7

| ID / 优先级 | 状态 | 已有实现与证据 | 仍需完成 / 证明 |
| --- | --- | --- | --- |
| V1 P0 类型 / headers | implemented | 自有扩展 webRequest、DOM / 多 frame、过滤分片 / 小预览、认证内存，E2 / E3。 | 公网 Service Worker / 跨域 iframe / 多 MSE，XML VAST / VMAP 正文识别未做；完整共享用例。 |
| V2 P0 胶囊 / 列表 | implemented | 本页数量、短片折叠、正在播放优先、清晰度，E3-P。 | 缩略图 / 来源站点、复制链接 / 缓存动作、数量滚动 / 空态、同页多视频标题与列表视觉。 |
| V3 P0 开播 / 全屏提示 | implemented | 进程内页面 guard、5s / 3s 胶囊，WebMediaInteractionTest。 | 同页只一次 / reload / iframe / SPA 全矩阵、优先视频区域坐标和完整动效。 |
| V4 P0 当前时间接管 | verified | `geckoDetectsNetworkTypesHeadersAndTransfersTheWebPosition`：实际网页 8s 暂停、Native 起播差 <2s、请求头 / Surface 尺寸，E3。 | 该证据限本地素材；公网认证及 DRM / MSE 边界仍见 V1。 |
| V5 P1 自动接管 | implemented | 默认关、前台有限时长 / 正尺寸 / 非 DRM 条件、防循环；E2 / E3。 | 网站 / Activity / 自动重试与直播矩阵。 |
| V6 P1 网页长按 | implemented | 500ms、配置、可信触摸 / 松手 / 关闭 / 让位；JS 与设备 E2。 | 真实站点既有手势兼容、所有取消路径与跨 frame 生命周期完整验收。 |
| V7 P1 清晰度 / 时长 | implemented | HLS / DASH / MP4 / TS SPS / AES 前缀、30d / 6h cache、4 / 2并发，E3-P；E4已有受控离线画质选择。 | 复杂 byte-range / encrypted init / 跨包 PMT、无尺寸 DASH、公网样本与完整离线/站点兼容矩阵。 |

### 播放器 P1–P23

| ID / 优先级 | 状态 | 已有实现与证据 | 仍需完成 / 证明 |
| --- | --- | --- | --- |
| P1 P0 格式；FLV P1 | implemented | Media3 1.11.1，MP4 / HLS / DASH 实播，下载 / 外部文件入口。 | WebM / FLV / mkv / 音频 / 外部 content-provider 真机、直播与长视频矩阵；外部 file:// 与 content:// 范围核对。 |
| P2 P0 长按倍速 | implemented | 播放时临时速度 / 松手恢复，真实触摸 / 可配置，E2。 | 表要求按住 400ms 内生效；当前依系统 longPressTimeout，须量化。暂停无效 / CANCEL / 失焦 / dispose 专项。 |
| P3 P1 按住后滑动换档 | pending | 当前长按中保持单一档。 | 每 40dp 切 1.5 / 2 / 2.5 / 3x，文字实时变化，禁止同时 seek / 调级。 |
| P4 P0 进度条 | implemented | 4 / 8dp、自绘三层基础、16dp thumb、时间预览、松手 seek。 | 气泡、按下 thumb 动效、任意点直接 seek 和完整触摸 / 可访问性专项。 |
| P5 P0 横滑 seek | implemented | 10dp slop、默认整宽120s、松手 seek。 | 虚拟时钟状态机、横竖竞争 / 范围边界、中央目标 / 总时长 / 差值卡片。 |
| P6 P1 缩略图 | pending | 无预览帧。 | 直链 / 本地 retriever、HLS 分片帧、池1、10s粒度 / 60张LRU，失败不留空框。 |
| P7 P0 双击 / 连击 | implemented | 38 / 24 / 38分区、280ms / 80dp、260ms单击、750ms连击，E2 / E3 部分触摸。 | 全时间边界 / 中区开关、同侧连击与方向翻转状态机测试、弧形涟漪 / 文字与等价按钮。 |
| P8 P0 亮度 / 音量 | implemented | 左右半区、75%高度、窗口亮度 / STREAM_MUSIC；已加等价滑块语义。 | 真实上下滑、静音 / 系统限制、手势开关、触感和状态恢复。 |
| P9 P0 流缓存 | implemented | 独立 SimpleCache LRU、签名参数规则、清单走网络、auth 摘要分区、私密绕过；E2 / E3。 | 真断网已看部分回拖、真实重签名 / 私密 / 缓存错误矩阵，8GB上限设置差异。 |
| P10 P0 预缓冲 | implemented | DefaultLoadControl、默认60s / 半数 min≥15s、起播1s / 重缓冲2.5s / 回退30s、DataSaver判断。 | 300s设置缺失；系统计费 / 白名单 / DataSaver设备专项。 |
| P11 P1 磁盘区段 | pending | 当前只画已播放和 bufferedPosition，不是 getCachedSpans。 | 不连续磁盘区段映射到时间、渲染和断网回拖对照。 |
| P12 P1 离线视频 | implemented | E4：真实DownloadManager/Service、HLS/DASH、独立非LRU缓存、停源音轨字幕和完成项强停后无网络UUID回放。E6：真实通知暂停/继续/取消及历史完成副本全片播放。 | 真机飞行模式 / 网络切换 / 后台 / 手机重启；下载进行中杀进程续传与重签名网站矩阵。 |
| P13 P1 超分 | implemented | E5：Anime4K S/M/L与FSR真实渲染、统计/暂停比较分割线、切档及旋转同一播放器。 | 指定 8 Gen2 级设备 540P动画→1080P均衡<12ms；完整GPU/生命周期兼容矩阵。 |
| P14 P1 超分 benchmark / 保护 | implemented | E5：真实720P片段、三档60真实帧和<16ms推荐/记忆代码；保护策略单测、实际shader错误直显。 | benchmark UI完整流程、倍速/HDR/PiP及真实热状态/过载设备矩阵；不能以策略单测代替硬件行为。 |
| P15 P0 解码 | implemented | MediaCodecSelector auto / hw / sw、保位置重建、失败软解一次；E3-T软件重建实播。 | 硬解初始化失败路径受控复现、切换提示、各解码器 / 网络认证重建和无中断验证。 |
| P16 P0 续播 | implemented | Room摘要键、10s/退出、>5s且距结尾>15s、从头按钮、私密不读写；E2 / E3。 | 具体时间“已从12:34继续”提示、可关记忆设置、进程杀死 / 全站边界。 |
| P17 P0 PiP / Session / 焦点 | implemented | PiP3操作 / sourceRect / auto开关、MediaSession、焦点 / noisy配置。 | Home / Back / 多窗口 / 进程死亡、耳机 / 蓝牙 / 锁屏 / 来电 / 别App抢焦点真机矩阵。 |
| P18 P1 后台播放 | pending | 当前无 MediaSessionService；非PiP onStop暂停。 | 默认关设置、服务 / 媒体通知、后台仅声音、会话控制和系统限制。 |
| P19 P0 锁 / 比例 / 速度 / 方向 | implemented | 锁方向、自动视频宽高比 / 手动旋转、适应 / 裁剪 / 拉伸、0.5–3x列表；E3布局。 | 16:9 / 4:3（P1）、默认比例持久化、锁后单击只显示锁2s、侧栏 / 音调失败提示与完整设备矩阵。 |
| P20 P1 片头片尾 | pending | 未实现。 | 域名+页面路径前缀记忆、±5s、设当前位置、跳过提示与误判边界。 |
| P21 P1 字幕 / 音轨 | implemented | HLS双音轨、内嵌VTT、SRT/VTT/ASS纯文本、样式、内存 / 重建；E3-T。 | DASH字幕、多 provider 的完整系统SAF、复杂轨道 / 直播变化、字幕避让控制层。ASS复杂特效按规范不要求。 |
| P22 P1 双指缩放 | pending | 当前第二指取消单指手势，不缩放。 | 1–3x、双指平移、接近适应 / 填充吸附、旋转 / PiP重算。 |
| P23 P2 截图 / 下一集 / 投屏 / MP4 | pending | 无GL截图 / playlist / DLNA / export。 | 分别实现；导出前先验证Transformer离线HLS再选Muxer备选，保留R6证据。 |

### 下载 D1–D7

| ID / 优先级 | 状态 | 已有实现与证据 | 仍需完成 / 证明 |
| --- | --- | --- | --- |
| D1 P0 下载页 | implemented | 分类 / 搜索 / 日期 / 多选 / 重命名 / 删除6s撤销，DownloadsUiTest。 | 今天 / 昨天 / 本周组、缩略图、文件管理器定位，跨离线记录统一后的排序 / 操作 / 撤销。 |
| D2 P0 确认 | implemented | 文件名推断 / 改名、SAF目录、默认下载目录、私密提示。 | API26–28 / provider / 权限恢复 / 重名 / 容量不足 / 全MIME专项。 |
| D3 P0 传输 | implemented | 分段 / Room / Range / checkpoint / ETag / 重试；E1曾500MB force-stop续传SHA通过。 | 当前最终源码重验500MB、手机重启 / 网络切换 / 真断网、分片重试3次退避、Basic/Bearer及源失效边界。 |
| D4 P0 通知 | implemented | FGS、分组、单项进度 / 暂停 / 续传 / 取消 / 完成打开，SRC。 | 真机通知权限 / 按钮 / 同时任务 / 系统FGS时限与无网络状态专项。 |
| D5 P1 动画 | pending | 菜单有活动数量，不是飞入箭头 / 菜单6dp进度点。 | §7.3飞入500ms / 弹跳及完成形变，减少动画模式。 |
| D6 P1 普通 / 离线统一 | implemented | E4/API35：同一ALL/VIDEO列表、日期、搜索、混合多选 / 删除撤销 / 重命名与点击播放；API实际暂停续传 / 取消、清流缓存保留离线。 | 界面暂停 / 继续 / 取消按钮的实际点击专项；验证范围为受控fixture / x86_64，真机通知与长时后台矩阵继续列在D4 / P12。 |
| D7 P1 存储条 | pending | 未实现。 | 应用已用 / 设备剩余准确计算、实时更新和可访问性。 |

## 第 2–6 / 8–10 / 12–14 节补充要求

这些条目保留第 11 节没有单独 ID 的明确要求；相同功能不会因分散在多节而漏验。

| ID / 来源 | 状态 | 要求、证据与下一步 |
| --- | --- | --- |
| ARCH-01 §2.1 / 3 | implemented | GeckoView / AC / Compose / Room / DataStore / OkHttp / 手写AppContainer已接。min26、target/compile37、release ARM64 / debug x86_64已构建；当前固定库版本须在最终发行核对稳定来源与完整依赖。 |
| ARCH-02 §3.1 | implemented | 现有模块包含superres；尚无app Navigator统一跳转接口。player→superres与downloads→offline存在feature依赖，跨模块跳转/依赖方向相对规范的偏差须处理或明确验收。 |
| ARCH-03 §3.2 | implemented | 独立PlayerActivity/BrowserStore、普通FGS与Media3离线DownloadService已有；后台播放PlayerService仍缺失。 |
| ARCH-04 §3.3 | implemented | 自有ensureBuiltIn bridge、native ports、all frame DOM、MSE原请求规则已有；uBO file install分离。需全生命周期与共享模块协议用例。 |
| ROUTE-01 §4 | implemented | NTP、tabs / downloads / history / bookmarks / settings / adblock与内部地址路由已有。播放器用不透明一次性token（保护私密，不应恢复明文src）；逐个验证内部route、外部VIEW/SEND/WEB_SEARCH与错误地址。 |
| UX-01 §1 / 5.1 | implemented | 无账号 / feed / 运营位、SplashScreen、原生首帧后建Gecko，符合现有API主线程创建约束。需首次引导、启动耗时；不能把“异步Application建Gecko”字面要求当作已满足，主线程API偏差须保留说明。 |
| UX-02 §5.2 | implemented | NTP共享输入、快捷方式编辑 / 添加 / 移除撤销、无痕隐藏已有。缺访问最多8项 / 系统语言预置核对、长按新 / 私密标签、P1语音与继续观看、P2壁纸。 |
| UX-03 §5.3 | implemented | 顶 / 底栏、焦点取消 / 全选、建议填入、进度条已有。缺eTLD+1 / 搜索词保持、适宜条件下剪贴板URL建议、长按复制 / 粘贴前往 / 分享、在线建议、自定义搜索模板 / 内联域名。 |
| UX-04 §5.3–5.4 | pending | E9已有三项站点权限面板、最终源码站点回归、真实系统允许/拒绝及近似定位专项证据；系统完整生命周期仍待。证书 / Cookie数据 / 本站uBO开关、外部intent:// / market:// / weixin://确认，拦弹窗Snackbar“始终允许”、文件选择及其他权限/页面提示仍须实现和验证。 |
| UX-05 §5.5 | implemented | 网格与撤销已有；拖拽排序P1、多选标签、全部关闭还原、favicon及明确滑动阈值待补。 |
| UX-06 §5.7 / 9 | implemented | 原版uBO站点唯一真源已保留。缺AddonUpdater、默认额外Cookie提示列表、logger入口和累计数据；不得直接改uBO存储冒充已接入。 |
| UX-07 §5.8 | implemented | 接管 / fail回网页 / 短视频折叠已有；og:title优先与同页编号、缩略图 / 站点 / 复制按钮、全DRM / blob / 超时返回网页保证待补。 |
| UX-08 §5.9–5.10 | implemented | 普通下载改名 / 分享 / 打开和历史书签已有。文件管理器定位、relative date、存储条、文件夹和原生favicon缺失。Places改SQLite已说明冲突，尚未取得最终偏差验收。 |
| UX-09 §5.12 | implemented | 私密FLAG_SECURE / 标签排除恢复 / 不写播放缓存进度已有。常驻“关闭所有无痕标签”通知缺失；显式离线下载仍可用但先提示文件保留，不能把私密禁止离线当作完成。 |
| THEME-01 §6.1–6.2 | implemented | 品牌浅深色、动态色默认关、JSON token、无痕配色已有。六预设选择器、组件硬编码色审查、完整player token / semantic体系需补。 |
| THEME-02 §6.3–6.4 | implemented | 系统字体 / typography / 4dp栅格、关键44/56dp胶囊 / 48dp点击区部分实现。所有数字tnum、行高、圆角、0dp tonal、面板≤90%屏高、Snackbar4s / 撤销6s须截图与测量核对。 |
| THEME-03 §6.5–6.6 | implemented | 当前品牌图标 / monochrome与来源文件已有。Material Symbols Rounded outlined风格、selected filled、真实favicon及fallback、下载 / 播放进度视觉全审查。 |
| PLAYER-01 §8.1 | implemented | 请求头清单 / 分片同传和跨来源认证隔离已E2 / E3验证。无改写清单；外部视频 / 本地 / 离线 / 直输询问各入口待最终完整验证。 |
| PLAYER-02 §8.2 | implemented | 竖屏16:9 / 标题 / 站点 / 按比例默认方向 / 手动全屏已有E3布局。缺竖屏本页其他视频列表、画质侧栏；P2选集 / 刘海画面延伸设置未做。 |
| PLAYER-03 §8.3 | implemented | 单击260、双击280/80、750连击、10dp slop在PlayerGestures集中。分区硬编码 / 所有手势常量统一、虚拟时钟边界测试、倍速失败Toast和P1换档 / 捏合仍缺。 |
| PLAYER-04 §8.4–8.5 | implemented | 内存缓冲 / 流缓存与默认预算已有；不连续磁盘区段 / 缩略图 / 全离线、缓存上限8GB、预缓冲300s、流 / offline互不影响待补。 |
| PLAYER-05 §8.6–8.7 | implemented | 自动 / 硬 / 软和一次fallback、续播 / PiP已有。后台服务、按站点跳片头尾、16:9 / 4:3、P2 FFmpeg AC3/EAC3/DTS扩展、GL截图 / DLNA / 导出未做。 |
| PLAYER-06 §8.8 / 13 | implemented | E5保留14份GLSL及MIT来源，原生HookShader/Renderer/SurfaceView、统计/同帧比较/错误回退/旋转已有实测；4778e46又验证真实三档benchmark和取消/后台释放。快速硬件应用推荐、温控/HDR/PiP/驱动矩阵及物理性能门未完成。 |
| PLAYER-07 §8.9 | implemented | 清晰度parser / cache / limit已有E3-P；实际高分辨率回写、同源header隔离有证据，E4已有受控离线画质选择。特殊封装、unknown失败边界和公网矩阵继续保留。 |
| PLAYER-08 §8.10 | implemented | 20s提示 / retry、401/403刷新一次 / boundedfallback / release防重建有设备证据。断网恢复、manifest fallback、所有起播失败恢复网页与公网授权轮换仍待验收。 |
| DOWNLOAD-01 §10.1 | implemented | >4MB Range多线程、默认4/可1–8、默认并发3/可1–5、退避 / validator / checkpoint。手机重启、系统网络、前台服务限制与普通Bearer嗅探待验。 |
| DOWNLOAD-02 §10.1 | implemented | 默认Download/Miru / MediaStore，SAF指定目录、Content-Disposition→path→MIME、重名、blob/data响应流。API26–28、provider重命名冲突 / 撤销后文件真删除、体积 / 加密 / 权限拒绝待验。 |
| DOWNLOAD-03 §10.2–10.3 | implemented | Room普通下载 / segments schema已有。离线kind=video统一字段、thumbnail_path、speed / ETA、所有分类扩展名（含视频m3u8 / APK组合包）核对。 |
| DATA-01 §12.1 | implemented | 无账号 / 云同步 / 主动telemetry初始化、Gecko notifyTelemetryPrefChanged(false)，备份排除，E0静态依赖记录。未做网络抓包；Crash Reporter上传禁用与本机日志导出入口 / 全依赖无遥测需最终证明。 |
| DATA-02 §12.1 | implemented | 下载 / 播放凭据仅内存，重启Cookie bridge重取，progress / resolution摘要分区。对Room / cache / logs / Intent / saved-state做真实敏感值取证，签名URL和referrer隐私单独审查。 |
| DATA-03 §12.1 | implemented | E8全局清理包含播放进度/播放缓存，范围及实际删除证据有限；默认搜索建议可关闭（在线建议尚未接）、默认关的退出清理仍未做。 |
| REUSE-01 §13–14 | implemented | 纯JS classifier、design/tokens.json、PlayerGestures集中常量已有。需扩充VAST共享用例、Swift token生成、完整手势 / shader / benchmark / Metal移植；不引入Cove片源协议。 |
| COPY-01 附录B | implemented | 已有中英strings、动词与单位大体一致。源码仍见直接“x / P / kind / ratio”等格式，需全可见文案 / 资源 / 单位与错误可操作性审查。 |

## 设置与默认值（§5.11 / 附录 A）

默认值已有静态配置不代表完整设置页已接入，也不代表即时生效已在所有系统状态验证。

| ID / 设置组 | 状态 | 基线事实与差异 |
| --- | --- | --- |
| SET-01 search.engine=google | implemented | 四内置引擎和DataStore；缺自定义%s、在线建议开关、首页数量 / 继续观看设置。 |
| SET-02 theme=system / dynamic=false / accent=ringoRed / toolbar=top | implemented | 前三默认及顶栏正确；accent还未作为用户设置，六预设 / 示意卡片未接。 |
| SET-03 blockThirdPartyCookies=true / httpsOnly=false | pending | 没有对应DataStore / UI，不以recommended tracking policy替代。DoH与退出清理同待。 |
| SET-04 adblock.enabled=true | implemented | 总开关保存并应用；首装稳定性 / 例外网站 / 高级控制和Cookie列表见A1–A4。 |
| SET-05 autoTakeover=false / webLongPressSpeed=true | implemented | DataStore与当前 / 新frame即时配置已验证。 |
| SET-06 doubleTapSeek=10 / longPressSpeed=2.0 / swipeRange=120 | implemented | 默认正确；UI seek5/10/15/30正确；hold只1.5/2/2.5/3，缺1.25起每0.25档；横滑范围缺90s。 |
| SET-07 defaultSpeed=1.0 / brightness=true / volume=true / centerPause=true / haptics=true | implemented | 初始速度1与四开关有代码；默认速度可设 / 保存缺失；开关系统状态与真实手势专项不全。 |
| SET-08 resume=true / backgroundPlay=false / autoPip=true | implemented | 默认续播、后台不继续、autoPiP正确；续播与后台设置 / Service未做，autoPiP全生命周期待验。 |
| SET-09 videoFit=contain / decoder=auto | implemented | 初始比例 / 解码正确；默认比例和默认解码偏好未持久化至设置页。 |
| SET-10 superRes=off / tier=balanced | implemented | PlayerPreferences默认OFF/BALANCED，DataStore保存；PlayerSettingsScreen有模式/档位/benchmark入口，E5已有受控渲染与界面证据。仍需最终版本全部设置持久化/重启和真实设备性能/保护矩阵；0.5.3 QUALITY benchmark全套超时保留。 |
| SET-11 streamLimitMb=1024 / prebufferSec=60 | implemented | 默认正确；上限实际4096MB / 180s，不符可选8GB / 300s。UI缺8GB和300s，额外15/180s不替代规范档。 |
| SET-12 ask=true / maxConcurrent=3 / threads=4 | implemented | 默认与可调范围有代码 / 部分设备证据。 |
| SET-13 videoWifiOnly=false / deleteFileWithRecord=true | implemented | 默认删文件选项已有；Wi-Fi-only开关 / 约束未做。删除6s撤销与同时删文件例外确认需offline统一后重验。 |
| SET-14 全开关即时生效 / 无保存按钮 | implemented | 现有DataStore开关遵循；所有新增设置和失败写入提示仍需验证。 |
| SET-15 站点设置 / 关于 | implemented | 关于版本 / 开发者 / 源码 / 许可 / uBO版本已有；站点页、日志导出、本地完整许可证展示及全部新组件告知待补。 |

### 同文档内的口径冲突与实现差异

| 编号 | 规范精确位置 | 审计结论 / 后续处理 |
| --- | --- | --- |
| SPEC-C01 长按生效时间 | §8.3 写“≥系统长按时长约400ms”，§11.4 P2 写“按住400ms内生效” | 近似系统阈值与严格400ms门不完全等价。当前使用系统timeout，不能推定满足严格门；后续需定时事件测量并明确采用可验证的判定常量，保留两条原文要求。§12.2“判定后<100ms”是另一段延迟，不可混为从按下计时。 |
| SPEC-C02 撤销窗口 | §5.5 关闭所有标签写“5秒内可全部恢复”，§6.6 含撤销Snackbar写“6秒” | 当前统一6秒，6秒窗口包含前5秒，但展示时长口径仍需明确。最终动效验收记录采用哪个展示值及原因，至少不得缩短5秒可恢复保证。 |
| SPEC-C03 Chip圆角 | §6.4 将Chip列在full胶囊组，§6.6 明确Chip为sm8 | 两个视觉值冲突，当前Material FilterChip不应同时被判两种形状全通过。优先明确组件专用§6.6口径并记录设计选择。 |
| SPEC-C04 历史 / 书签库与隐私 | §2.1 / §5.10 指定Places，§12.1 要移除Glean / Sync | 当前依赖解析发现Places引入不期望模块，已在PROGRESS解释SQLite偏差。功能CRUD通过不能视为指定存储技术要求也通过；最终须维护者认可偏差或找到符合两条约束的实现。 |
| SPEC-D01 长按档位 | §5.11 全局长按可设1.25–3.0/步0.25；§8.3 按住后左右滑换1.5/2/2.5/3 | 它们是设置值与手势快捷档两个不同要求，不是允许缺1.25 / 1.75等设置项的理由。 |
| SPEC-D02 缓存 / 预缓冲 | §5.11 8GB / 300s可选；§8.5默认1GB / 60s，附录A同默认 | 默认与范围不冲突。当前4GB / 180s上限是实现差异，需要修正，不能把默认正确当作范围符合。 |
| SPEC-D03 控制层隐藏 | §7.4 写4秒，PlayerScreen目前3秒 | 规范没有另一处3秒约定；这是实现差异，需修正并验证。 |

上述问题保留原始要求与待决证据，不自行修改规范来消除差异。

## 第 7 节动效与触感逐场景

`MiruMotion` 和 `animationsReduced()` 已存在，但前者未被所有组件一致引用；后者只在组合时读取系统开关，不能据此证明运行时切换与所有循环动画降级。下列各行均需最终录屏 / 帧数据 / 降低动画测试，不能只验Compose结构。

| ID / §7.3 浏览器场景 | 状态 | 待完成或证据边界 |
| --- | --- | --- |
| MB01 NTP搜索框→地址栏共享元素 | pending | 当前共享输入状态不等同位置 / 宽度 / Logo上移16dp形变。 |
| MB02 网页地址栏聚焦展开 | implemented | 编辑覆盖已有；展开、按钮0.8缩放淡出、左图标交叉变换需对齐300ms。 |
| MB03 建议逐项20ms错开 | pending | 没有足够实现 / 运行证据。 |
| MB04 加载条spring / 100%停150ms | implemented | E0 UI修正已实现；最终帧与2dp / 退出200ms核对。 |
| MB05 工具栏跟手隐藏 | pending | B10。 |
| MB06 横滑换标签截图跟手 | pending | B16。 |
| MB07 盾牌数字滚动 / 0→有拦截变色 | implemented | 真实计数已接；滚动 / 颜色时序未全实现验收。 |
| MB08 视频胶囊宽0 / 0.6→1 | implemented | 胶囊有出现入口；弹簧让位 / 数字变化待对齐。 |
| MB09 打开标签容器变换 | pending | 网格已做，容器变换未接。 |
| MB10 从卡片进入页面逆变换 | pending | 同上。 |
| MB11 卡片横滑透明 / spring补位 | implemented | 关闭 / 撤销有证据；当前速度承接、透明曲线 / 邻卡补位待核对。 |
| MB12 从+新建卡片展开 | pending | 新建行为已有，空间动画未验。 |
| MB13 菜单锚点0.85→1 | implemented | Material Dropdown；指定原点 / 时长 / 不逐项需核对。 |
| MB14 底部面板 / scrim | implemented | Material ModalBottomSheet；最大高度 / 时长 / 减动画需验。 |
| MB15 设置子页共享轴X ±30dp | implemented | 主路由叠层已有；所有子页逐路径 / 中断动画需验。 |
| MB16 一级全屏页FadeThrough y+24 | implemented | 目前以共享路由动画为主；需与§7.3区分并核对。 |
| MB17 下载箭头飞入菜单 / 6dp点 | pending | D5。 |
| MB18 下载完成progress→勾路径形变 | pending | 状态文字更新不等同指定动效。 |
| MB19 书签星标1→1.25→1 | pending | outlined / filled切换存在，弹簧点亮未验。 |
| MB20 Snackbar y+16 / 撤销淡出 | implemented | 单Host / 6s撤销已有，空间 / token / 4s普通停留需验。 |
| MB21 下拉刷新 / 阈值触感 | pending | B10。 |
| MB22 无痕背景交叉过渡 | implemented | 配色切换已做；250ms交叉需验。 |
| MB23 Chip背景 / 勾选展开 | implemented | 使用Material Chip，需token / 边界对齐。 |
| MB24 列表删除收高 / 淡出 / 上移 | implemented | 删除 / 撤销行为已验证，250ms视觉仍待。 |

| ID / §7.4 播放场景 | 状态 | 待完成或证据边界 |
| --- | --- | --- |
| MP01 控制层fade+上下8dp / 中央0.9 | implemented | 有fade；上下8dp、中央缩放、token未完整。 |
| MP02 播放4s自动隐藏 / 暂停不隐藏 | implemented | **代码为3s，与规范4s不同**；须修正并虚拟时钟验。 |
| MP03 播放暂停路径或交叉缩放 | pending | 当前直接切换Icon。 |
| MP04 双击弧形36% / 三箭头450ms | pending | 有累加反馈文字，未移植完整弧形视觉 / 三箭头。 |
| MP05 长按顶中胶囊 / 800ms箭头 | pending | 有反馈文本，非规范胶囊与流动箭头。 |
| MP06 横滑玻璃时间卡 / 差值 / 帧 | implemented | 当前中央时间文字；总时长 / 差值 / 预览与玻璃样式待。 |
| MP07 亮度音量200dp玻璃条 / 700ms | implemented | 当前反馈文字、清理延迟750ms；需图标 / 5dp条 / 700ms及静音。 |
| MP08 进度4→8 / thumb0→16 / 气泡 | implemented | 尺寸交互基础已有，thumb持续可见、动画 / 气泡待核对。 |
| MP09 右侧320/200ms侧栏 | pending | 当前倍速 / 设置 / 字幕为AlertDialog；需360dp≤85%右侧栏。 |
| MP10 侧栏内容200ms交叉 | pending | 依赖MP09。 |
| MP11 锁形变 / 单击仅锁2s | implemented | 锁 / 方向已有，其余隐藏；指定单击 / 图标动效待。 |
| MP12 左下玻璃Toast2.2s | pending | 当前多为系统Toast / 中央文字，无统一播放器提示层。 |
| MP13 >300ms缓冲 / 实时网速 | implemented | 300ms延迟 / 40dp / 2.5dp已实现；缺网速与指定fade。 |
| MP14 P2最后30s下一集卡 | pending | 依赖playlist。 |
| MP15 入播放器共享元素或黑底0.92→1 | pending | 独立Activity已有，指定入场未验。 |
| MP16 超分可拖对比分割线 | implemented | P13/E5：实际暂停同帧比较像素差、SetProgress可访问操作通过；触摸与TalkBack完整专项待补。 |
| MP17 系统PiP / sourceRect | implemented | E2已有sourceRect / PiP动作；旋转 / 实际缩放连贯仍需真机。 |

| ID / 通用规则 / §7.5 | 状态 | 待完成或证据边界 |
| --- | --- | --- |
| MOT01 集中token / 进减速 / 退出0.7倍 | implemented | MiruMotion常量齐；player硬编码时长 / fade与所有组件映射未全对齐。 |
| MOT02 跟手释放spring承接当前速度 | pending | 全手势动画专项，不能固定tween替代。 |
| MOT03 移除动画时150ms交叉 / 循环静态 | implemented | 有系统scale读取 / 部分route降级；全流程 / 运行中更改和所有循环未验。 |
| MOT04 不中断输入 / 从当前值接续 | pending | 逐动画中断交互测试；无足够直接证据。 |
| MOT05 60/90/120Hz / 主线程无IO | pending | 真机帧数据、StrictMode / trace，不能用模拟器“看起来流畅”验收。 |
| HAP01 各动作触感和系统开关 | implemented | 开关已有；PlayerTouch目前统一CLOCK_TICK。需长按LONG_PRESS、双击中心CONTEXT_CLICK、阈值GESTURE_THRESHOLD / fallback、锁CONFIRM，尊重系统关闭。 |

## 性能、隐私、发布与运行验收门

| ID / 来源 | 状态 | 必须取得的证据 / 当前事实 |
| --- | --- | --- |
| PERF01 §12.2 NTP<800ms | pending | 骁龙7 Gen1级 / 8GB、release冷启、可交互事件trace；模拟器am start-W不能替代。 |
| PERF02 恢复页首次绘制<2.0s | pending | 同设备冷启恢复真实网页的首绘事件、网络条件说明、多轮分位数。 |
| PERF03 NTP90Hz无掉帧 | pending | 90Hz真机FrameTimeline / Jankstats + 滚动动画场景。 |
| PERF04 标签面板<100ms开始动画 | pending | 手势事件→首动画帧，预先生成缩略图证据。 |
| PERF05 良好网络HLS起播<1.5s | pending | 真实首画 / 首音时间，不是ready布尔值；受控网络和release真机。 |
| PERF06 判定后倍速<100ms | pending | 手势判定时间→实际PlaybackParameters事件；并与P2按下400ms要求分开计。 |
| PERF07 10标签内存 / Gecko回收 | pending | 10个实际内容标签、后台回收 / 恢复 / 峰值内存，应用不额外限标签。 |
| PERF08 ARM64 APK<110MB | verified（仅0.5.3体积/打包） | 源码7521c04最终APK **107,897,008字节**，SHA `aca04bda…baf8956`；1517 payload逐项保留、SDK对齐/原证书一致、仅17个ARM64库，原4079 Gecko资源+5官方blocklist保持，E8。当前仍为Android Debug签名，正式签名/ARM64真机与完整发行门另列；不覆盖后续B13源码。 |
| PERF09 超分均衡<12ms | pending | 指定8Gen2级设备、540P动画→1080P、稳定统计含保护；与设置benchmark<16ms推荐门分别验。 |
| PRIV01 无账号 / Glean / Sync / FxA / 上传 | implemented | E0静态图与源码有证据；完整实际依赖、崩溃上传配置、抓包 / 日志取证、关于本机日志导出待补。 |
| PRIV02 Cookie / Authorization不落盘 | implemented | 普通下载 / playback / media models内存、分区摘要；需最终Room / prefs / savedstate / files / logs取证和重启恢复认证测试。 |
| PRIV03 私密全生命周期 | implemented | E0–E3局部历史 / Cookiejar / 缓存 / 进度 / 截图有证据；关标签 / process death / no残留全范围未证。 |
| ACC01 TalkBack完整任务 | pending | 打开网页→切换标签→下载→播放暂停→调倍速的实机读屏路径；仅contentDescription存在不够。 |
| ACC02 所有图标名称 / 48dp / 等价手势控件 | implemented | 部分按钮 / slider语义已有测试；完整树、锁 / 双击跳转 / 缩放等所有等价动作需审。 |
| VIS01 深浅×地址栏顶底×字体1.3x | pending | 按§15.2各组合真实截图，检查遮挡 / 截断 / 点击区；初始中文英文截图不替代。 |
| VIS02 移除动画全流程 | pending | 运行系统scale=0的全操作链，静态循环 / 跟手 / 无输入阻塞。 |
| DEV01 模拟器+真机仪器测试 | implemented | API35多批受控测试；最新完整0.5.3为77/79。0.5.4最终production源码无trace站点/混合下载7/7（46.626秒），fresh5590真实系统专项3/3（21.741秒）；保留全部早期失败，没有0.5.4完整回归或release通过证据。API26–28 / 当前系统 / ARM64物理机仍缺。 |
| SITE01 第11所有真实网站 / 登录 | pending | B1六站及R4媒体站点的兼容矩阵；需要真实账户时由人提供可用会话 / 完成认证，不猜测通过。 |
| REL01 发布构建 / AAB或按ABI拆分 | implemented | arm64 release启R8 / shrink、APK已构建；AAB分发验证、实际签名和release运行验收未完。 |
| REL02 正式签名 | pending | 基线无keystore.properties，现包Android Debug证书；需维护者提供安全签名配置，不在源码 / 日志中存秘密。 |
| REL03 完整第三方告知 / 对应源码 | pending | LEGAL仅旧快照；uBO GPL、过滤列表、Gecko嵌套依赖、Media3和将来shader真实产物须重新审查；关于需可取到全部许可和准确源码。 |
| REL04 图标 / 素材来源 | implemented | 原画、trace脚本、PROVENANCE与项目许可排除说明已有；发布时维护者权利 / 服务条款确认仍保留，不因AI生成推定全授权。 |
| REL05 每批验证后Git提交 | implemented | 已有42daa92累积源码、fbca123离线、0941e3a安装状态修复、1432d83超分、35ad373通知、036e285回归修正、4778e46 benchmark专项提交；每批验证+文档后本地commit并报告hash。未授权自动push / 商店发布。 |
| DEMO01 每里程碑操作录屏 | implemented | 仅发现`artifacts/m0/miru-m0.mp4`，约3MB；它未覆盖后续全部验收。M1/M2/M3/M4分别需screenrecord与步骤 / 版本 / SHA记录。 |

## M0–M5 总门与 iOS

| 里程碑 | 状态 | 仍缺的完成证明 |
| --- | --- | --- |
| M0 骨架 | implemented | 不能把“已进入M3开发”当作M0所有P0通过；B1/B3/B4/B10/B11/B13/B19/B20及通用UI / 真机门未闭合。 |
| M1 拦截与下载 | implemented | 首装uBO、A1/d3ward、全部冷启动 / 真机、通知 / 网络 / 手机重启、完整下载动效和阶段录屏。 |
| M2 播放核心 | implemented | 全P0手势 / 時间门 / 系统焦点 / DataSaver / 外部文件 / 公网矩阵、UI动效、真机与阶段录屏。 |
| M3 画质与离线 | implemented | 清晰度/字幕音轨、受控离线与超分渲染有E3/E4/E5；缩略图、完整性能/真机矩阵和阶段录屏仍未完成。 |
| M4 打磨 / 发布 | pending | §7全部动效、§5.11全部设置、无障碍、§12指标、正式release / 许可门和录屏。 |
| M5 iOS | pending | 规范明确列出，Android M4后启动；需独立原生工程及以下全部平台替代实现 / 编译 / 运行 / 真机验收。 |

| iOS ID / §14 | 状态 | 原始要求与下一步 |
| --- | --- | --- |
| IOS01 工程 / UI | pending | 独立SwiftUI外壳 + UIKit WKWebView / 播放手势；共享交互 / 主题 / 动效；当前无ios工程。 |
| IOS02 标签 / 私密 | pending | 每标签WKWebView，私密nonPersistent，完整标签 / 会话 / 数据清理行为。 |
| IOS03 广告拦截 | pending | 同批规则转WKContentRuleList，每list≤15万条，元素 / 脚本WKUserScript；SafariConverterLib许可复核；界面称“广告拦截”，不能宣称运行uBO。 |
| IOS04 嗅探 / Cookie | pending | document_start hookXHR / fetch / HTMLMediaElement.src / MediaSource，ScriptMessageHandler，WKHTTPCookieStore；复用纯JS分类与同用例。 |
| IOS05 播放 / Metal | pending | AVPlayer + 自绘控制，所有手势数值 / 行为与Android一致，复用Cove Metal超分 / benchmark / 保护。 |
| IOS06 流缓存 / 离线 | pending | AVAssetResourceLoaderDelegate或回环代理 / 分片缓存；AVAssetDownloadURLSession离线，私密隔离和断网完整播放。 |
| IOS07 下载 | pending | URLSession后台传输、自建统一下载页、暂停 / 恢复 / 系统生命周期、文件与数据隐私。 |
| IOS08 工具链 / 真机 / 发布 | pending | 需要macOS + Xcode / Apple SDK / simulator / 真机及签名配置；Windows源码编辑不等于iOS build / runtime验收。 |

## 第 16 节风险门

| ID | 状态 | 当前结论 / 仍需证据 |
| --- | --- | --- |
| R1 上游活跃 / 版本匹配 | implemented | 固定AC158.0.2 / 同版Gecko、reference基线和UPSTREAM已记录，工程构建成功；最终升级 / 依赖来源核对保留。 |
| R2 官方XPI安装 | implemented | E1保留首装超时历史，E7补官方blocklist资源后有离线/在线fresh-profile通过；多轮/真机/更新仍待，不能用禁签名/blocklist替代修复。 |
| R3 popup / picker | implemented | 原版popup路径已用；element picker完整实际可行路径仍无证据。 |
| R4 webRequest范围 | implemented | 本地frame / HLS / DASH / MP4有证据，SW / 公网站点待；YouTube不接管按规范是预期，不能作所有媒体100%成功要求。 |
| R5 鉴权 / 签名 | implemented | 401 / 403刷新一次、失败回网页、释放期间不重建已测；真实站点登录Cookie / token轮换需补。 |
| R6 Transformer离线导出 | pending | P2实现前先验证支持；失败再用TS+Muxer，不预承诺能无重编码导出。 |
| R7 GL SurfaceView同步 / HDR | implemented | E5实际同帧比较、暂停重绘/旋转/故障回退；HDR策略存在，真实HDR/驱动矩阵仍待补。 |
| R8 ABI体积 | verified（仅0.5.3打包） | PERF08：源码7521c04最终107,897,008字节<110,000,000，只有17个ARM64库；仍需正式签名/真机与后续版本重验。 |
| R9 uBO / Gecko分发许可 | pending | 全部第三方 / 列表 / 嵌套库实际产物审查与人工确认；旧M0开源检查不能覆盖含uBO发行。 |
| R10 Google Play视频下载政策 | pending（条件项） | 仅在决定上架Play时需当时政策与站点限制核对；当前本地构建没有商店发布证据。保留此门，不主动上架。 |

## 下一阶段顺序与外部条件

1. 提交已验证基线后推进P12 / D6：真实HLS / DASH离线、选择画质、独立缓存、下载页完整管理、断网完整实播；保留普通MP4引擎与私密显式下载警告。
2. 按M3完成缩略图、磁盘缓存区段、Cove超分 / benchmark / 热保护；并尽早修复A1/A6首装稳定性以及B10/B11/B13/B20等P0缺口，避免把旧阶段债务推到最终验收。
3. 对齐完整设置范围、P1手势 / 后台 / 跳片头尾 / 文件夹 / PWA等，保留P2实现清单；完成所有动效、无障碍 / 真机 / 公网矩阵、性能 / 大小 / 许可 / 正式签名门与各阶段录屏。
4. Android M4有逐门证据后再进入M5。iOS不是当前Windows能完成编译验收的平台，但仍在本规范范围中；可在可用Mac工具链到位前准备可共享JS、token、纯逻辑和原生源码，不能把未编译 / 未运行写成验收通过。

本次环境只确认Windows、Android SDK / JDK与可启动的API35模拟器能力；审计时ADB没有已连接物理手机，未发现`xcodebuild` / `swift`命令，没有iOS工程和正式签名配置。真实Android设备、90/120Hz / 指定SoC超分性能、登录验证所需账户、macOS / Apple SDK / iOS设备、维护者签名与分发许可人工确认是外部验收条件。它们不妨碍继续实现和验证其他可执行工作，也不能凭缺少外部条件就将完整目标标记完成。

## 更新规则

每批结束时记录：提交hash、确切源版本、设备 / ABI / OS、执行命令、测试日志、失败 / 排除项、截图 / 录屏、APK SHA与签名。将证据连回本矩阵的具体行，只提升被新证据完整覆盖的要求；不得把代码存在、构建通过、测试被排除或模拟器局部成功提升成全量验收。
