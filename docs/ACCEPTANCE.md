# 全量验收矩阵

审计日期：2026-10-23（Asia/Shanghai）。需求来源为 [MIRU-SPEC.md](../MIRU-SPEC.md) 全文，包含第 11 节、其他章节的细节与附录，不仅是已经实现的功能。

原始审计基线为 **42daa92 / 0.4.0**；0.4.1 离线新增证据见 E4，后续超分见 E5，并只更新被实际覆盖的条目。不能把开发中的文件、旧版本测试或绿色构建等同于最终验收。M1 首装仍按专项记录验证，完整阶段均未宣称完成。

状态定义：

- `verified`：该行所列的具体要求已有相称的直接证据，证据范围在表中明确；不会自动提升为整个里程碑完成。
- `implemented`：已有部分或全部代码，但验收仍有缺项、差异或未验证平台。
- `pending`：尚未实现、缺少足够证据，或已有结果明确不达标。括号说明失败 / 依赖条件。

P0 是首个可用版本门，P1 是第二轮功能，P2 在规范中为“有余力再做”。P2 仍逐项保留，不会把“未做”写成“已验收”；如果维护者决定不纳入某次发行，需明确记录发行范围，而不能删去原需求。iOS 也是规范内的 M5，顺序为 Android M4 之后，不从目标中静默排除。

## 当前未完摘要（2026-11-17）

第11节的67个功能ID中，**3 verified、54 implemented、10 pending**。
补充要求、设置、动效、隐私、性能、发布、风险和iOS表另列，且部分要求相互引用。
`implemented`仍需读该行的“仍需完成 / 证明”，不能按已验收计算；`pending`也可能已有大量局部实现，
例如B13，并非全部从零开始。

| 核心pending ID | 尚未闭合的范围 |
| --- | --- |
| B13、B16、B18 | 完整权限、提示和上传矩阵，工具栏横滑，主屏/PWA |
| B22、B23、B24（P2） | 标签组/最近关闭；阅读模式；翻译 |
| A5 | 累计拦截统计和logger入口 |
| P11 | 磁盘缓存时间区段 |
| P23（P2） | 截图、下一集、投屏、MP4导出 |
| D5 | 下载动效 |

54个`implemented`条目还包括实际功能缺项，例如查询保持、自定义搜索模板、
完整设置范围，以及网站、系统生命周期、provider与解码器兼容验证。第一组日常功能已经实现，
在线建议/内联域名、网站图标、六预设、无痕通知和首次引导的专项证据见E40。当时的
163项常规回归有160项通过、3项失败，保留两项超分超时和历史图标显示失败的记录。同包图标隔离2/2通过不抵消整套失败。§7动效、无障碍、字体/主题
组合、敏感数据实际取证仍需各自证据。专项性能/隐私/发布/运行表24行仅PERF08在明确打包范围内
verified，另9 implemented、14 pending；与上述67项不相加计算进度。

最近完整常规0.5.18固定同包147/147（1004.529秒）通过；四个独立前置类与capability包仍排除不计通过。
该版ARM64开发包108,152,186字节及完全相同DEX的x86非debuggable受控两项运行通过（E33），
与完整147、独立99/15分别保留输入边界。旧0.5.15完整145/146、139/146与0.5.17选定失败均保留。
全P11、完整R8/ARM64物理机、正式签名、手机性能、分发审查和阶段录屏仍待。
**M0–M3均未整阶段验收，M4/M5仍pending，IOS01–IOS08全pending。**

以上0.5.18为原摘要保留的历史输入。0.5.20完整151项与Release记录见E38/E39；第一组0.5.21/code29
固定659项源码无漂移，JVM263通过/1符号链接权限跳过/0失败、Debug/Release Lint均0错误/29警告。
ARM64开发包108,468,497字节、1548项payload/对齐/17库/Gecko资源验证通过；未做本版ARM64或
相同DEX的x86 Release运行验收。常规160/163失败记录和同包通知权限1/1另列E40，隔离通过不抵消整套失败。当前排序以AGENTS.md为准，P11/P2冻结。

第二组0.5.22/code30的构建、专项与常规回归记录见E41。Debug和ARM64 Release的711项源码输入一致。全模块JVM报告288通过、1跳过，Debug和Release Lint均0错误、29警告。108,595,529字节ARM64开发包通过内容、对齐、签名、原生库和Gecko资源验证，运行与正式签名仍待。完整常规回归171/194通过，23项失败，优先定位影响登录态下载、图片复制和网页视频识别的桥接启动问题。本站数据清理仍在WIP。

第三组的后台播放、双指缩放、长按换档、片头片尾、安全设置、书签文件夹和下载存储条已实现。0.5.23/code31 常规复跑 201/201 通过，原失败轮仍保留。ARM64 打包与第三组专项见 E42，真机运行及完整规范验收仍待。

第四组 0.5.24/code32 的 §7 动效与触感、播放器右侧抽屉面板/双击弧形反馈/长按倍速胶囊/横滑预览卡/左下玻璃提示/4s 控制栏自动隐藏、§5.11 设置补全与站点权限总览、§15.2 无障碍语义审计与 1.3x 字体 Snackbar 修复及 `PlayerTouch` 双击抬手到按下间隔修复已实现，见 E43。现存模块 JVM 315 通过/1 因 Windows 符号链接权限跳过/0 失败，Debug 与 Release Lint 均为 0 错误/29 警告；108,972,642 字节 ARM64 开发包通过 1,626 项 payload、16 KB 对齐、签名、17 个 AArch64 库与 51 项 Gecko 资源验证。最终同源 Debug 包在模拟器跑完常规 257 项得 253/257（3,317.553 秒），整套不记为全绿；前两轮 252/257、两组隔离 8/9 失败与 4 个排除前置类及 capability 包均如实保留，隔离复测不拼接也不抵消整套失败；真机运行、高刷与性能指标、正式签名与 M4 录屏仍待。

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
| E9 | [B13-PERMISSIONS-VALIDATION.md](B13-PERMISSIONS-VALIDATION.md)、`artifacts/browser-core/054-*` | 0.5.4首批已提交97d8be1（overlay f3117fb、相邻删除诊断d593ee2）：固定Observer提前reject根因与只读前后对照；neutral2/2（21.221秒）、早期站点7/7（61.002秒）、最终源码非trace站点/混合下载7/7（46.626秒）、fresh5590真实系统权限3/3（21.741秒）；全部旧失败保留。release构建/1517 payload/对齐/同证书/17个ARM64库/4078+1+5 Gecko资源和107,966,640字节打包验证通过。固定0.5.4 APK常规回归87/87通过（641.35秒），另排除两个强停重启类及独立系统权限类；没有完整B13、正式签名或ARM64运行通过声明。 |
| E10 | [B13-PROMPTS-VALIDATION.md](B13-PROMPTS-VALIDATION.md)、[B13-PROMPTS-TEST.md](B13-PROMPTS-TEST.md)、`artifacts/browser-core/055-prompts-*` | 0.5.5页面提示首批：真实alert/confirm/text、beforeunload、POST重发、HTTP401、Activity重建和旧token；原生callback身份修复后7/7（33.102秒），最新连续提示抑制及真实蒙层专项整类9/9（57.245秒），所有旧失败保留。普通/私密合跑11/11（49.725秒），保留FLAG_SECURE；Debug构建/JVM111项及最终lint0错误/22警告通过。2048cec的ARM64开发包107,987,120字节、1517 payload/签名/对齐/17个库/4078+1+5资源验证通过；固定APK常规98/98通过（595.555秒），fresh系统权限独立3/3（21.844秒），强停重启两类仍排除；正式发行及完整B13仍待。 |
| E11 | [B13-FILE-UPLOAD-VALIDATION.md](B13-FILE-UPLOAD-VALIDATION.md)、[B13-DOCUMENT-LIFECYCLE-VALIDATION.md](B13-DOCUMENT-LIFECYCLE-VALIDATION.md)、`artifacts/browser-core/056-*` | 0.5.6/56a22a1上传首批8/8（81.741秒），真实Gecko/系统DocumentsUI/provider/服务端字节、同名多选、重建、取消、hash、同源/私密owner清理及READ/WRITE释放。固定APK完整106为102过4失败（1328.953秒）：uBO READY、两项定位重新询问、QUALITY benchmark；上传/页面提示均通过。fresh首装1/1、系统3/3独立通过；JVM123项、lint0错误/22警告、R8构建及108,007,656字节ARM64打包/1518payload/17库/4077+2+5资源通过。旧85/106及所有失败保留，隔离复跑不抵消整套失败。完整上传矩阵/发行运行仍待，不能扩大为目录、capture、全MIME/保存状态/初始blank上传已通过。 |
| E12 | [M1-057-STARTUP-VALIDATION.md](M1-057-STARTUP-VALIDATION.md)、`artifacts/browser-core/057-*` | 0.5.7/041ec81修复迁移期间OnReady无listener，保持GEO屏障且防伪READY。JVM141项、lint0错误/22警告；fresh首装1/1、已安装两轮各1/1、独立系统3/3、站点6/6。固定APK完整106为103过3失败（883.116秒），uBO/定位/提示/上传通过；余底栏新文档页顶、复制后重开菜单、QUALITY在45秒仅45有效帧。ARM64开发包108,011,752字节/1518payload/17库/4077+2+5资源通过。旧失败、非空早期交接、真机及发行运行边界保留。 |
| E13 | [B13-FILE-UPLOAD-VALIDATION.md](B13-FILE-UPLOAD-VALIDATION.md)、`057-mime-*`与`057-grid-behavior-*` | 生产仍为041ec81/0.5.7，仅扩展测试；原上传8+五种accept的真实系统过滤/元数据/网络字节，最终整类13/13（146.829秒）。纯Java provider新增不透明BIN负例，图片grid按真实row description适配，首轮两项驱动失败保留。APK/输入逐轮分开，不拼成旧106项全绿；capture、目录、其他提供方/网站与完整生命周期仍待。 |
| E14 | [B13-CAPTURE-VALIDATION.md](B13-CAPTURE-VALIDATION.md)、`artifacts/browser-core/058-*` | 0.5.8真实Camera2拍照/录像2/2（46.552秒），原EXTRA_OUTPUT只读FD格式/字节与网页POST匹配、外部UID READ/WRITE回收、文档租约结束删除；整类原8+MIME5+capture2为15/15（170.368秒）。924c97a后仅扩展测试，`058-capture-lifecycle-first-input/result.json`及对应device/runtime日志另证生命周期3/3（65.015秒）：真实拍照review取消保留旧FileList和上传字节、关闭录像owner后真实迟到Done排空旧slot且下一owner独立上传、录制中Main/receiver实际重建后仅交付一次。主APK仍为`e1be264beb62f9c0002a7be995d4b6b10ed8ab1109b528cc711242742f0faad9`，新测试APK为`469015d65347cf1b4a39ba8736c7117f892d92bd0ad524c0e86834739be3629d`；同一新测试APK随后运行`058-upload-lifecycle-complete-device.log`，整类18/18通过（199.276秒），不是将独立轮次拼接；仍不代表完整应用回归。JVM141项、lint0错误/22警告；接收器Cancel/Back交manager决定结束。仅拍照data URI为空（Intent非空），空Intent、录音、私密capture和其余生命周期矩阵仍待；dbddaed的R8/shrink release构建成功（2分50秒），ARM64开发包108,011,752字节、SHA `bf92e7a4f4b69893f25632e4057b5d80f7777a27f3fbcc27a58a541c1ecd062c`，1518payload/对齐/同Debug证书/17库/4077+2+5资源验证通过；30b9f42固定APK随后在新受控5600常规完整回归117/117通过（907.736秒），含旧浏览器两项与QUALITY；旧失败保留，清单发现不计执行/首次启动证据。两个外部重启类仍待；另在全新5602首次目标进程uBO1/1（36.811秒）、真实系统权限独立3/3（33.353秒）通过，无pm grant/预授权，不合并进117。R8/ARM64运行、正式签名及物理性能门仍待。 |
| E15 | [B13-FOLDER-UPLOAD-VALIDATION.md](B13-FOLDER-UPLOAD-VALIDATION.md)、[BROWSER-CORE-VALIDATION.md](BROWSER-CORE-VALIDATION.md)、`artifacts/folder-upload/059-*` | 0.5.9/code17目录首批：真实DocumentsUI TREE→公开单根提交→Gecko真正二次确认；深8层/根名/Unicode/不同路径同名/空及零字节/相对路径/name/type/size/网页POST原bytes、私密owner关闭、空根真实change、native DENY和显式MIME不保真的整体失败，整类5/5（50.447秒）。保留首次驱动1方法2失败（56.136秒）及XML，修正后原方法1/1（21.03秒）。跨模块原48/49（338.146秒）唯一旧顶栏SDK页顶门失败保留；测试显式TOP及真实doc/rAF/精确GET/实际刷新后，Core7/7（150.868秒）、最终同包单次49/49（602.313秒），不是完整常规回归。主SHA `6616ef8afcc4cf29b97e457943f59f9e3704efcd369a4cca76be433cbe51130b`、最终测试SHA `3585f319bc85b1c94058207798515a21b4a003cc759ce9f5353dbde796d16230`；engine90/90含树10/10、lint0错误/22警告，仅API35/x86_64受控provider。特殊MIME/metadata、完整目录生命周期及native异常、其他provider仍待；后续0.5.9的R8构建/ARM64打包见E16；本版完整常规回归、R8/ARM64运行及正式发行仍待。 |
| E16 | [B13-AUDIO-CAPTURE-VALIDATION.md](B13-AUDIO-CAPTURE-VALIDATION.md)、`artifacts/b13-upload-lifecycle/audio-capture-integration/` | 独立AudioCaptureIntegrationTest复用原夹具，原File18不变。固定官方Fossify1.7.3实际Record/Save、临时READ观察、原FD/provider元数据/AAC结构、外部原文件与网页POST三方bytes、READ回收/原文档租约删除/外部源保留；0.5.8为1/1（23.74秒），当前0.5.9为1/1（45.525秒），原31ef失败52.453秒保留，修复仅测试shell执行/清理。当前G主F097/testE4E5与C目录包身份分别记录；同commit主包DEX/native/omni相同、13项assets仅行尾不同。不合并进目录49或旧117。当前全模块JVM151项0失败/错误/跳过（engine90 FROM-CACHE，其余有测试模块UP-TO-DATE），另补纯JVM common:test；lint0/22。hostmic实际OFF，虚拟输入不证明物理拾音。15c2943的R8/shrink构建6分31秒通过，ARM64开发包108,024,040字节、SHA `2f691037ce171e9edd7df915979fc45a2915c6d587f467d73e31c06f8598265d`，1518payload/对齐/同Debug证书/17库/4077+2+5资源均通过。录音完整取消/私密/生命周期/其他handler及本版常规完整、R8/ARM64运行与正式发行仍待。 |
| E17 | [B13-PREPARED-FOLDER-VALIDATION.md](B13-PREPARED-FOLDER-VALIDATION.md)、[目录元数据合同](B13-PREPARED-FOLDER-CONTRACT.md)、`artifacts/folder-upload/0510-*` | 0.5.10/code18：真实TREE/Gecko二次确认与网页FileList/POST覆盖12份重复路径/同名目录/显式MIME/0、负及未知mtime完整多重集；metadata正向1/1（23.148秒）。新增TREE Back保留原FileList/lease/bytes、原生确认时重建及迟到旧系统结果；后者不等同迟到native prompt。engine104中103通过/1 Windows链接跳过；Android实际leaf/ancestor/unlisted链接及snapshot重检3项通过。保留单次10项9/10（105.094秒）、随后13项12/13（70.64秒）、授权窗口驱动后13项12/13（95.756秒）：最后唯一失败为virtual负例page准备超时，未触发TREE，其他12项通过；隔离负例1/1不抵消整组失败。主SHA987845/test0489固定，lint0/22；随后同包常规完整130/130通过（734.586秒），含本批全部13项及文件18/benchmark2/GL3；原专项失败保留，四个独立前置类仍排除不计通过。完整B13、其他provider/网站、进程死亡、迟到native、独立前置/真机矩阵及本版R8/ARM64运行仍待。 |
| E18 | [B13目录后续冷页/系统权限](B13-PREPARED-FOLDER-VALIDATION.md)、`artifacts/folder-upload/0510-fresh-followup-20261030-1421/` | 0.5.10主987845未变，仅测试page失败快照，新test01b2a4。新实例5556安装前无Miru、实际hostmic OFF、首次目标进程直接原virtual负例1/1（14.305秒）；无失败快照触发，不宣称旧超时根因修复或uBO首装已验。该实例随后实际Android系统摄像头拒绝、摄像头/麦克风允许后网站许可、近似定位3/3（16.692秒），无pm grant/预授权。此test只证明独立专项，不把两轮拼成4项或合并成旧130项全套；系统永久拒绝/完整生命周期/私密与真机仍待。 |
| E19 | [M3-SEEK-PREVIEW-VALIDATION.md](M3-SEEK-PREVIEW-VALIDATION.md)、[候选及旧失败](M3-SEEK-PREVIEW-PREFLIGHT.md)、`artifacts/m3-thumbnails/0511-*` | 0.5.11/code19缩略图：单ExoPlayer提帧替代示意MMR/ImageReader方案，完整保留10秒/60张LRU和时间fallback。主496f5b首轮test85078d播放器JVM27/27、lint0/22，owned cancel1/1（1.518秒）、close1/1（1.122秒），同次adapter22/22（25.675秒）含六格式/PTS/RGB、5/6秒真peer关闭/队列恢复、跨源剥离/私密/缓存分区与停源离线。首UI3/3（32.967秒）；仅测试补充后新test527b62，同次UI5/5（29.456秒）含真UP一次seek/Cancel零seek、playing持DOWN>3秒及真403时间无空框fallback，主播放/私密磁盘保持。原裸库两项失败保留，各输入不拼接；随后S2同包常规135/135通过（881.462秒），四个独立类和capability包排除不计通过。506f177干净源R8/lint成功（4分钟，0/22），108,115,322字节ARM64开发包1538payload/对齐/同Debug证书/17库/4077+2+5资源验证通过；ARM64/R8运行、正式签名和真机兼容仍待。 |
| E20 | [磁盘区段基础层与完整计划](M3-CACHED-RANGES-PLAN.md)、[MP4公开表预验及原失败](M3-CACHED-RANGES-PREFLIGHT.md)、`artifacts/m3-cached-ranges/`（详见各文档的工作树） | aef8a06：精确字节依赖核心、选中AV交集、IO committed-span监听及source key factory提取；播放器JVM首53/53、补计算中取消与真实线程竞争后55/55，lint0/22，非真实磁盘/UI证明。独立MP4能力基线3/3（0.166秒）；另复杂3项2过1失败（0.281秒），强VBR/B帧头尾通过，edit与ffprobe PTS差约16.7ms。原断言不改，独立单edit诊断仍失败（0.218秒）；265项字节/flags/hash全等，实际Media3 PTS均吻合独立stts/ctts/elst，FFmpeg另作首呈现点归零。不同轮次/APK不拼接，P11完整格式映射、实际缓存/解码/停源seek与UI仍pending。 |
| E21 | [主源资源绑定与真实磁盘基础验证](M3-CACHE-BINDINGS-VALIDATION.md)、`artifacts/m3-cached-ranges/bindings-*` | 原DataSpec key/position/length绑定接入main session，preview不改主绑定，private/offline无stream绑定；前两轮JVM70/70、lint0/22，独立player首轮JUnit初始化失败（实际0用例），修测试后真实磁盘10/10（1.166秒）。build-02应用专项23/23（180.774秒）。复查补失败后迟到EOF的原反例1项确实失败，修复为失败终结observation；最终build-03 JVM71/71、lint0/22，独立真实磁盘11/11（2.090秒），另同包4类应用专项23/23（180.213秒）。含未提交/partial/异常、受控时间洞、commit/remove/clear、session和迟到EOF。时间依赖由测试指定，WAV只证实际source路径，不是格式映射、视频解码PTS/RGB或P11/UI验收；各轮输入与失败均保留，专项不是完整应用套件。 |
| E22 | [HLS清单观察与时间依赖映射](M3-HLS-CACHED-RANGES-VALIDATION.md)、`artifacts/m3-cached-ranges/hls-index-*`与`hls-fixture-independence/` | 官方1.11.1 tracker实际主源包装/全公开接口转发、最终snapshot与代次、原始请求资源的时间/依赖映射；三轮544项输入无漂移，JVM71项保持、lint0/22。独立设备首25/25、补null/错位门后27/27，最终仅扩测试同轮30/30（5.819秒，HLS19+绑定11），含真实TS/fMP4/AES/BYTERANGE空洞、init/key移除和private/offline/preview观察隔离。主/应用测试包在后两轮SHA完全相同，4类应用回归20/20（278.542秒）。派生独立清单有固定宿主工具9份独立首帧/关键包证明，未改原素材；不代替Android磁盘seek解码。当前未串联实际选轨发布、其他格式或磁盘UI，P11仍pending。 |
| E23 | [0.5.12普通HLS磁盘进度条](M3-CACHED-TIMELINE-UI-VALIDATION.md)、`artifacts/m3-cached-ranges/selection-*` | 固定1.11.1 GroupKey规范化清除ID/metadata，实际日志定位原选轨6/8、6/10失败，改为wrapper/索引/downstream/实际组绑定。最终JVM72/72，模块同轮41/41（15.390秒），UI同包4/4（31.983秒），含原native码率/语言旧缓存不补新洞、父取消不等待cache monitor、真实画布/内存16秒/磁盘120秒、停供503后93秒真实PTS/RGB蓝帧及89秒缺口失败、private无层及解码重建。Source551项冻结输入无漂移；最终同包常规139/139（941.713秒）通过，四个独立前置类及capability仍排除不计通过。完整P11其他格式、资源闭合/复杂HLS/真机仍pending，各失败/输入保留。 5475738干净源R8/lint成功5分49秒，552项源无漂移；108,127,610字节ARM64包1538payload/对齐/同Debug证书/17库/4077+2+5资源验证通过，R8/ARM64实际运行及正式签名仍待。 |
| E24 | [0.5.13普通DASH磁盘区段与布局](M3-DASH-CACHED-RANGES-VALIDATION.md)、`artifacts/m3-cached-ranges/dash-*` | 实际holder/原始父DataSpec的Template/Timeline/List/Base+sidx、PTO、period边界、merged子range、SAP旁路证明、原生选轨与AV交集；早期模块59/59。保留多period设置错误、两轮UI黑帧、过严物理尺寸诊断及首个布局候选比例失败。最终build-10冻结566项输入、JVM72/72、lint0/22、59项素材逐字节一致；控制器保存最近实际尺寸供layout-only，当前7/7 UI（33.159秒）含停供新decoder的9秒PTS/蓝帧、比例门及HLS4；最终同次模块59/59（50.155秒），误填类名的29过/2启动失败轮保留。同包常规142/142（1465.085秒）通过，四个独立前置类及capability仍排除不计通过。83f2da9干净源R8/lint成功7分48秒、0/22、567项输入无漂移；108,135,802字节ARM64开发包1538payload/对齐/同Debug证书/17库/4077+2+5资源验证通过。完整P11、R8/ARM64实际运行及正式签名仍待。 |
| E25 | [0.5.14普通流跨会话资源闭合](M3-CACHE-RESOURCE-CLOSURE-VALIDATION.md)、`artifacts/m3-cached-ranges/closure-*` | 原始坐标/main headers key、当前SDK持久长度与committed spans、失败观察保留、IO动态依赖登记及实际主源/preview metadata事件；DASH普通单段/无CMCD配置补未访问资源。加强旧包UI为5过/3完整holes失败；首候选UI8/8，但模块66过/1失败（删除最后span也删除SDK长度），原失败保留。修为真实EOF恢复及metadata-only事件，第二候选模块69/69、UI8/8；另补清空后旧EOF256/新长度512反例。最终build-03冻结570项、JVM74/74、lint0/22、素材59项一致；最终同次模块70/70（23.273秒）、UI8/8（36.863秒）；同包常规143/143（954.367秒）通过，四个独立前置类及capability包仍排除不计通过。cf1c52a干净源R8/lint成功4分2秒，571项无漂移、0/22；108,135,802字节ARM64开发包1538payload/对齐/同Debug证书/17库/4077+2+5资源核验通过。P11全格式/复杂流、整窗口跨period、完整生命周期/Range/鉴权、正式签名与真机运行仍pending。 |
| E26 | [MP4原生冷启动读路径预验](M3-MP4-NATIVE-SEEK-PREFLIGHT.md)、`artifacts/m3-cached-ranges/mp4-native-*` | 仅新增独立测试，0.5.14主490c9c逐字节未变，新test5c74fb；572项冻结/JVM74/lint0/22。owned 120样本精确移除[8482,15032)，真实HTTP/SDK seed后停供，新PlayerActivity在9秒实际PTS9000000/RGB[1,0,255]且无新增上游；5秒实际10117/8482洞内503并error。显式独立2/2（8.894秒），不并入旧143项；无生产MP4索引/UI，也不抵消原edit严格比较失败。 |
| E27 | [MP4主源观察与缓存结构解析](M3-MP4-CACHE-METADATA-VALIDATION.md)、`artifacts/m3-cached-ranges/mp4-observer-*`/`mp4-metadata-*` | 实际默认extractor/输出/原DataSpec身份观察，完整公开工厂转发及preview/private/offline/ICY隔离；只读固定key缓存结构读取和公共BoxParser，未发布MP4区段。编译旧类名/数组错误、安装前0测试中止、原81过/3 raw-edit参考模式失败均保留；按独立elst整数tick修新oracle，无容差且旧edit门未改。最终579项输入/JVM74/lint0/22/69素材保持；同次模块84/84（27.859秒），同main/app-test另native2/2（15.044秒）；新主b59ecb同包常规143/143（799.742秒）另已通过，四个独立前置类及capability仍排除；未作新R8/打包声明。实际native来源校验、duration/DTS/decode/I/O闭包、AV与时间条仍待。 |
| E28 | [MP4读取签名与raw时序](M3-MP4-PROVENANCE-TIMING-VALIDATION.md)、`artifacts/m3-cached-ranges/mp4-proof-*`/`mp4-timing-*` | 按实际DataSource实例记录原open/key和metadata返回字节SHA，完成native输出后freeze、非MP4停止、readonly逐段验证/冲突拒绝；原始stts/ctts RLE整数PTS/DTS/duration，非邻接PTS推算。最终584项无漂移/JVM74/lint0/22/69素材；同次94/94（20.443秒），同候选native独立2/2（9.430秒），不拼入旧143。新主d52939尚无整组/R8打包通过声明；未完成post-edit/PCM/RAP/AV/完整IO闭包与MP4区段发布，P11仍pending。 |
| E29 | [0.5.15单视频AVC磁盘时间轴](M3-MP4-AVC-CACHED-TIMELINE-VALIDATION.md)、`artifacts/m3-cached-ranges/mp4-ui-*` | 实际native owner/选轨、metadata读取签名、raw tick端点、IDR/GOP及单视频读取依赖接入生产PlayerCachedTimeline。最终build-07冻结592项、JVM74/74、lint0/27；71项owned素材逐字节保持。模块同次97/97（47.120秒）、HLS5+DASH3+MP4三项UI同包11/11（69.909秒）；120秒长片完整11段holes、memory15.9秒、实际Canvas84/94亮于90秒缺口，停供9秒PTS9000000/RGB蓝帧且metadata清理不重建player。保留早期long蓝帧失败，新增稳定窗口/Surface前提没有放宽8秒/RGB门。完整常规146为145过/1失败（1133.900秒），失败为超分benchmark QUALITY超时，MP4三项通过；本版R8/ARM64打包和运行仍待，P11多轨/复杂edit/其他封装与完整矩阵保持pending。 |
| E30 | [Benchmark采样/释放阶段期限](M3-BENCHMARK-PHASE-DEADLINE-VALIDATION.md)、`artifacts/m3-cached-ranges/benchmark-release-*` | 保留原45秒/60帧采样门，完成有效测量后独立10秒EGL释放期限，重复/迟到不可续期且回调仍核对owner/epoch；双语释放错误。602项冻结输入、JVM79/79含新增5项阶段边界、lint0/27。新输入SwiftShader专项仍1过/2（QUALITY只有25帧），不抵消原整组37帧/原隔离60帧释放跨期的不同失败。同包在只读宿主GPU API35/x86_64实例实际2/2（28.231秒），三档62/63/61帧及无推荐/偏好保持/取消后台释放通过；未作物理SoC性能或ARM64声明。新模块97/97（30.750秒）、界面11/11（52.873秒），完整常规139/146（2616.657秒），7项失败保留，不计整组全绿。 |
| E31 | [0.5.16 R8实际运行与启动修复](R8-RUNTIME-VALIDATION.md)、`artifacts/r8-device-smoke/` | SnakeYAML2.2静态logger依赖包名，原0.5.15 R8浏览器启动空指针；只保留两个包名，类/成员仍重命名与缩减。603项冻结输入（b990c4c加审查的版本/规则两项变更），ARM64 R8/lint3分1秒、0/22；108,152,186字节、1538payload/对齐/同Debug证书/17库/Gecko完整性通过。x86包唯一DEX与ARM64逐字节同SHA bc8fc7a4...；非debuggable目标实际公开seek红帧、销毁后停源缓存重开、真实Gecko标题两项2/2（runner105.616秒）通过。保留初次测试APK运行库污染、旧包Gecko崩溃和红帧观察失败，不作全R8/ARM64物理机/正式签名声明。0.5.16 Debug整组仍待。 |
| E32 | [0.5.17缓存回拖源错误恢复](M3-CACHED-SEEK-RECOVERY-VALIDATION.md)、`artifacts/m3-cached-ranges/cached-seek-*` | 真实未来缺口503后原包seek仍IDLE/error2004，新增回归0/1；显式HTTP5xx回拖保持player/session/播放意图并prepare，独立鉴权/解码路径及20秒身份/代次检查保持。最终615项/JVM79/lint0/27；恢复用例两轮PTS9/RGB/完整holes/像素/清metadata通过。选定15项首14/15、最终13/15，短片多一次缺口503违反原零上游门，超分起播30秒超时，均保留未过。模块97/97（30.261秒）通过，完整147、新R8/ARM64及全P11仍待。另确认宿主qemu在nvoglv64.dll中断，不把中断3个status0算7项通过；runner transport-lost路径不再追加logcat，host四种失败/成功路径已验。 |
| E33 | [0.5.18 Progressive初始seek调度](M3-PROGRESSIVE-STARTUP-VALIDATION.md)、`artifacts/m3-cached-ranges/startup-*` | 真实native加载线程在准备回调前越过小缓存前缀：受控默认1MiB/4KiB两项2/2，验证实际upstream8482与独立9秒offset。普通流main Progressive采用公开4KiB继续加载检查，parser/seek/key不改。616项无漂移、JVM79/lint0/27；模块99/99（32.046秒）、同包选定15/15（135.838秒），短片原零上游门、PTS/RGB、完整holes/像素/清理、鉴权及其他六项通过。同包完整147/147（1004.529秒）通过；a47f1e4干净源R8/lint0/27，108,152,186字节ARM64开发包1538payload/对齐/同证书/17库/Gecko验证通过，完全相同DEX的x86非debuggable目标受控2/2（runner21.259秒）。性能/其他封装、全P11及ARM64物理机/全R8/正式签名仍待，不以当前通过替代完整目标。 |
| E34 | [MP4多轨读取调度对照](M3-MP4-READ-SCHEDULE-VALIDATION.md)、`artifacts/m3-cached-ranges/mp4-read-schedule-*` | 固定1.11.1累计PTS/字节、10MiB读超前、256KiB重开及T35优先策略移植，新增JVM11项，完整player90/90。owned AVC+双AAC的独立raw packet真值，native四seek后全部样本输出次序/PTS/实际消费字节/parent对照通过；模块100/100（45.718秒）、lint0/27。原JVM反例配置错误和导入编译失败保留。尚未接入生产AV区段/解码/preroll/UI，不借旧147或R8证明当前新包，P11仍pending。 |
| E35 | [MP4 AV部分缓存解码预验](M3-MP4-AV-DECODE-PREFLIGHT.md)、`artifacts/m3-cached-ranges/mp4-av-native-*` | 真实owned AVC+双AAC全量/4–8秒packet物理洞、删除原文件后新player9秒冷启动，en/ja真实owner选轨、原生首视频PTS9与YUV蓝帧，实际sink PCM窗口≥18000样本/频率440或880±5Hz/RMS门通过。625项/JVM90/lint0/27，合并104/104（27.935秒）；原nullable编译、RGBA/YUV进程崩溃和renderer时基PCM0失败保留。未证明精确初始AAC/preroll/gapless或每个时间单元，未接生产AV灰条，不替代完整P11/新整组和Release。 |
| E36 | [0.5.19 AVC/AAC磁盘区段候选](M3-MP4-AV-CACHED-TIMELINE-VALIDATION.md)、`artifacts/m3-cached-ranges/mp4-av-index-*`/`mp4-av-interior-red-01` | 生产native owner/raw tick/IDR/全部轨道IO闭包；内部9.010秒缺AAC字节却仍被覆盖的反例0/1保留，按真实decode-only及EOS末帧规则修正微秒单元依赖。628项冻结/JVM90/lint0/27，同包模块109/109（66.099秒），含9项AV真实PCM/蓝帧、4项新player字节洞错误与1272单元/1908次native seek/51067次消费读取核对。前一输入MP4 UI6/6含AV停源9秒/5秒洞和same-player清metadata；最终同包应用专项17/17（202.295秒），完整149首轮中断无终态，恢复软件GPU后同包149/149（1114.743秒、0跳过，既定四个前置类及capability排除不计通过），长AV磁盘像素/性能、精确preroll/复杂流及新Release/物理机继续，完整P11仍pending。 |
| E37 | [120秒AV画布与查询优化](M3-MP4-AV-LONG-VALIDATION.md)、`artifacts/m3-cached-ranges/mp4-av-long-*` | owned 1200视频＋双5169 AAC逐包固定；原11项native过但索引12.1/8.8秒，新增长AV UI0/2等待磁盘层超时，原失败保留。按实际父坐标限256项key记忆、唯一metadata查询；631项冻结/JVM90/lint0/27，同包模块111/111（76.310秒）、MP4 UI8/8（67.853秒），本次full/partial索引1.60/1.21秒。真实memory15.9秒，画布84/90/94秒亮度117.7/51.7/117.7，11段holes及同player清理、未来503后新PTS9/蓝帧通过。新151常规150过/1混合下载撤销失败，完整时序保留、同包隔离1/1不抵消；旧149不借用；更长片长/取消/内存、精确preroll/EOS、复杂格式/矩阵和新Release/物理机仍待。 |
| E38 | [0.5.20单视频边界与撤销焦点](M3-SEEK-UNDO-BOUNDARY-VALIDATION.md)、`artifacts/m3-cached-ranges/seek-undo-fix-*`及两条before负例 | 单视频3.950秒需要4秒绿帧，8960字节洞实际新player错误2005却仍被覆盖的旧包2过/1失败保留；普通单视频使用native时间单元闭包，已知末视频帧在单/AV尾部均实际PTS11.9蓝帧。旧搜索Focused=true断言0/1，结合键盘动画和丢失action时序，下载多选/删除clearFocus，不改6秒/3秒门。633项冻结/JVM90/lint0/27，模块114/114（81.009秒）、同包MP4八项＋混合下载9/9（67.117秒）通过；同包完整151/151（1262.152秒，0跳过，既定独立前置/capability排除不计通过）；重排/复杂流、AAC精确输出、全P11及新Release/物理机仍待。 |
| E39 | [0.5.20 Release与受控运行](M3-SEEK-UNDO-RELEASE-VALIDATION.md)、`artifacts/m3-cached-ranges/seek-undo-release-build`、`artifacts/r8-device-smoke/seek-undo-*` | 25559eb与151项Debug应用输入一致，634项完整冻结无漂移；ARM64 R8/shrink/lint2分38秒、0/27。108156282字节开发签名包的1538payload/16KB对齐/同Debug证书/17AArch64库/Gecko4077+2+5通过。x86唯一DEX逐字节同SHA4864a7a3…，框架探针无目标应用类/额外运行库，公开单视频缓存重开、真实Gecko页、AV完整预热后9秒蓝帧停源新Activity重开3/3（35.907秒），AV206[0,176805]且重开前后请求各1。harness打包错误与四轮2/3导航/预热失败保留；不作PCM、部分洞/R8灰条、ARM64 native、全部R8或正式签名/全规范声明。 |
| E40 | [第一组日常浏览检查点](DAILY-BROWSER-CHECKPOINT.md)、`artifacts/m3-cached-ranges/daily-browser-*`及各功能专项 | 地址栏3、favicon2、六色2、无痕通知2+权限1、引导3项模拟器功能专项已过。0.5.21同源659项冻结，JVM263过/1因Windows符号链接权限跳过，Lint0/29；108468497字节ARM64开发包1548项payload/16KB对齐/17AArch64库/Gecko资源通过。163项常规160过/3失败（两项超分超时、历史图标显示超时）；同快照通知权限1/1、图标隔离2/2通过，均不抵消整套失败；真机/公网/完整R8和正式签名未证，不提升整个功能ID或阶段。 |
| E41 | [第二组网页权限与uBlock检查点](BROWSER-PERMISSIONS-CHECKPOINT.md)、`artifacts/m3-cached-ranges/browser-permissions-checkpoint-*` | 0.5.22同源711项，JVM288过/1跳过，Lint0/29。108,595,529字节ARM64开发包1549项内容/16KB对齐/17个AArch64库/Gecko资源通过。常规171/194，23项失败、零跳过，前两轮宿主GPU中止单列。新增常规31/31、uBlock常规1/1，摄像头、客户端证书真实认证、uBlock联网/离线首装与冷启动各自留专项证据。真机、完整B13与正式发行仍待。 |
| E42 | [第三组日常 P1 检查点](DAILY-P1-CHECKPOINT.md)、`artifacts/m3-cached-ranges/daily-p1-checkpoint-*` | 0.5.23 同源 741 项，JVM 299 项通过、1 跳过，Lint 0 错误、29 警告。108,743,102 字节 ARM64 开发包通过 1,624 项内容、16 KB 对齐、签名、17 个 AArch64 库及 Gecko 资源验证。同一固定 Debug 包的常规复跑 201/201，另有第三组能力专项 9/9，首轮系统 UI 自动化连接异常及失败记录保留。真机运行和正式签名仍待。 |
| E43 | `artifacts/group4-checkpoint-0.5.24/`、`artifacts/releases/Miru-0.5.24-arm64.apk.verification-20261125-133507-5809289b` | 0.5.24/code32 第四组打磨检查点：同源现存模块 JVM 315 通过/1 Windows 符号链接跳过/0 失败，Debug 与 Release Lint 均 0 错误/29 警告。最终主包 `199c4d3fb5f747c6a93f18308226c85e3dbc72f9227c0e97267873fc6538a67d`（477,193,565 字节）、测试包 `8a5fc036bc404777017c5015ac661fcc72516c43d16ef3858845d44cbd3b82ee`（12,414,726 字节）、原 R8 包 `b8d91c1f67282bc812e0b1ef6e9fce9eb4496a1fb4a89e03655f9e865da999b0`（112,097,911 字节）、ARM64 开发包 `a6e5fbf076dd293e81b27928150b7578f1dc72db5f8b5f8d459a6647bf8a85aa`（108,972,642 字节，< 110 MB），`verify_apk_payloads.py` 通过 1,626 项 payload/16 KB 对齐/Debug 证书/17 AArch64 库/51 项资源。前两轮常规回归 252/257（3,070.604s）与 252/257（3,353.823s）保留；最终包同包常规 253/257（3,317.553s，失败 `BookmarkFolders` 旧书签残留、`BottomSheetLayout` 系统 `animator_duration_scale=null` 致遮罩未变、`Media#geckoDetectsNetworkTypes...` 超时、`SeekPreview#playingDrag...` 12 秒短片播完；第四组新增专项与 `PagePrompt` 9/9、`Certificate` 3/3、`OmniboxSuggestions` 3/3 均过）。同包隔离 `Media` 6/6+`PlayerMotion` 1/1+`PlayerHoldSpeed` 2/2 合计 9/9（214.831s），`isolated-final-recheck` 8/9（175.4s，`BookmarkFolders` 1/1 与 `SeekPreview` 5/5 过、`BottomSheetLayout` 2/3 因倍率留空），恢复 `animator_duration_scale=1.0` 后 `isolated-final-recheck-2` 8/9（200.719s，`BookmarkFolders` 1/1 与 `BottomSheetLayout` 3/3 过、`SeekPreview` 4/5 因 12 秒短片到达 EOF）。隔离通过不拼接亦不抵消常规 253/257 失败；四个前置类及 capability 仍排除；限 API35/x86_64 模拟器与 Debug 签名，真机运行、ARM64 Release 运行与正式发行仍待。 |
| SRC | 当前模块源码、Gradle、manifest、资源和 `design/tokens.json` | 证明存在代码 / 配置。单独不能证明运行正确、无泄漏、无掉帧或兼容所有站点。 |
| LEGAL | [OPEN-SOURCE-REVIEW.md](OPEN-SOURCE-REVIEW.md)、[third_party/README.md](../third_party/README.md) | M0 源码公开范围的检查和后续依赖说明；不是含 uBO / 嵌套依赖的完整二进制分发审查。 |

E3 常规设备套件明确排除 `AdblockIntegrationTest`（首装偶发 240 秒超时）与 `DownloadRestartTest`（需要分阶段外部 force-stop）。排除的项目仍未通过，不能以 38/38 抵消它们。历史验证结果须按版本保留，最终交付还要重跑相应范围。

## 第 11 节功能 ID

### 浏览器 B1–B24

| ID / 优先级 | 状态 | 已有实现与证据 | 仍需完成 / 证明 |
| --- | --- | --- | --- |
| B1 P0 网页浏览 | implemented | `BrowserController` / GeckoView；E0、E3 的真实本地导航。 | Google、YouTube、Bilibili、知乎、GitHub、淘宝移动版正常浏览和登录；真实网站媒体 / 权限 / 文件交互。 |
| B2 P0 多标签 / 后台 / 恢复 | implemented | BrowserStore 单一状态源、SessionStorage；E0 force-stop 后页二恢复并可回页一。 | 当前交付完整多标签与历史栈杀进程回归、后台标签 / 低内存 / 设备重启矩阵。 |
| B3 P0 地址栏 | implemented | URL / 中文搜索、历史 / 书签 / 已开标签建议；E40补当前四引擎在线建议/最多5条/填入、取消旧请求、关闭开关与无痕禁用，历史域名高亮补全/退格/按原协议打开；E43补自定义 `%s` 模板校验（选中自定义时停用建议）与非编辑态长按复制/粘贴前往/分享菜单。 | 查询保持、eTLD+1 展示、剪贴板自动建议，实际搜索提供商和手机网络兼容。 |
| B4 P0 NTP | implemented | 原生 Logo / 同输入状态 / 快捷方式，E0 / BrowserUiTest；E43 补快捷方式显示开关及长按“在新标签页/无痕标签页打开”。 | 指定中端真机冷启动可交互 <800 ms；访问频率排序与完整 §5.2 行为。 |
| B5 P0 标签切换器 | implemented | 2 列、当前描边、普通 / 无痕、缩略图、关闭撤销；E0 / E3；E43 补筛选胶囊动画与滑动越过阈值触感反馈。 | 40% / 1000 dp/s 阈值和当前速度 spring、关闭后导航栈撤销、关全组完整恢复、容器变换。 |
| B6 P0 主菜单 | implemented | `MiruMenu` 260 dp、导航 / 书签 / 下载 / 分享 / 查找 / 桌面站点 / 设置；E43 补开启退出清理数据时显示“退出”项及清理流程。 | §5.6 全部适用项；添加主屏入口目前不可用，P2 最近标签未做；动效 / 状态逐项核对。 |
| B7 P0 历史 | verified | `BrowsingLibraryTest` / BrowserUiTest / E0 / E3 覆盖搜索、日期分组、删 / 清与撤销。 | 此行仅核心 CRUD；favicon、清 Cookie / 缓存入口和 Places 偏差另列。 |
| B8 P0 书签；文件夹 P1 | implemented | 星标、编辑、删除撤销及建议见 E0/E3，图标见 E40。E42 补默认「移动设备书签」、多级文件夹、跨文件夹搜索和 SQLite 升级保留。 | 星标动画、真实网站图标兼容、真机与 Places 偏差。 |
| B9 P0 无痕 | implemented | 私密标签不恢复、FLAG_SECURE、不写历史 / 播放进度 / 流缓存、Cookie jar 分离，E0–E3；E40补真实通知后台关闭/普通标签保留/禁止撤销、Cookie清除、通知权限拒绝及重新授权。 | 关最后无痕标签后表单 / 内存私密状态完整清理审计、所有返回 / 下载路径与系统版本/真机矩阵；Android14+可手动划掉通知，拒绝权限时不显示。 |
| B10 P0 下拉刷新 / 隐藏栏 | implemented | E8：真实Gecko compositor滚动、APZ允许接管、SwipeRefreshFeature+M3指示、DynamicToolbar负裁剪；顶底栏滚动/页顶刷新通过。E15显式TOP/位置、唯一新doc双rAF及精确GET/实际刷新，Core7/7并随49/49集成通过；SDK缓存数值只作诊断。 | 网站既有手势/嵌套滚动、固定底部元素、键盘/焦点/旋转/字体完整真机矩阵。 |
| B11 P0 上下文菜单 | implemented | E8：真实Engine hit链接/图片/视频菜单；链接文字、HTTP图片原字节/私密敏感剪贴板、新无痕标签、SVG只读URI通过。 | blob原文档图片完整动作、实际跨应用分享、视频原生播放/下载及各项菜单入口全部专项；站点已有手势兼容。 |
| B12 P0 页内查找 | verified | `BrowserUiTest` 通过真实 Gecko 查找；聚焦键盘、计数 / 上下 / 关闭已有。 | 保留全站 / 长页回归。 |
| B13 P0 权限 / 站点设置 | pending | E9/97d8be1三项权限面板、普通/私密选择与B20联动；真实音视频live、临时GEO新文档再问。站点/混合下载7/7、真实系统3/3；生命周期2/2证明Activity重建保留请求、CAMERA撤销后录制停止且MIC选择保留。E10提示普通9/9、普通/私密11/11。E11文件上传原8，E13五种accept过滤/字节，E14拍照/录像及capture生命周期，上传18/18。E15目录5/5及跨模块49/49。E16真实录音，E17 prepared MIME/mtime/重复路径和目录生命周期，E18 fresh/系统权限。E41补弹窗、外部App确认、表单选择与日期/颜色、网站通知、自动播放、证书查看、公开RSA证书真实认证、Widevine许可，以及系统摄像头取消/永久拒绝恢复/重建/迟到结果的专项。 | 本站数据清理按维护者选择留在WIP。麦克风/定位系统恢复、真机和其他Android版本、完整权限持久化与私密/多页活动流撤销矩阵、真实远程通知和加密视频播放、企业证书仍待。文件上传还需其他provider、原生拒绝保留旧选择、完整两阶段/迟到原生回调、进程死亡及其他capture handler矩阵。 |
| B14 P0 默认浏览器 / Intent | implemented | RoleManager、http(s)、SEND 文字 / URL、WEB_SEARCH；E40补一屏可跳过引导、真实系统设默认/取消、已默认状态、外链旋转后保留且不重复开标签；修复Role请求未用ActivityResult导致系统取不到调用方。 | 外部返回来源 App、协议拉起确认及文件类型矩阵；API26–28和真机行为。 |
| B15 P1 地址栏顶 / 底 | implemented | DataStore 即时生效、键盘避让，E0 截图；E43 补可视化“顶部/底部”位置示意选择卡片与无障碍语义（`SettingsCompletionIntegrationTest`）。 | 完整主题 / 大字号 / 旋转 / 表单真机设备矩阵。 |
| B16 P1 横滑工具栏 | pending | 无接入。 | 相邻标签截图跟手、阈值、速度 spring 与无标签边界。 |
| B17 P0 桌面版 | implemented | 切 UA / reload、菜单复选框。 | 受控 UA 断言和实际站点 / 恢复行为验收。 |
| B18 P1 主屏幕 / PWA | pending | 菜单枚举存在，动作未接入。 | AC PWA、系统添加和安装后的启动 / icon / scope。 |
| B19 P0 主题 | implemented | 系统 / 浅 / 深、动态取色、品牌色与无痕色；E40补六预设UI/持久化/即时生效、动态色与预设恢复、Activity重建/无痕深色、主色按钮文字对比度。 | 深浅×顶底×字体1.3x组合、完整颜色token/图标审查、真机与API26–28动态色降级。 |
| B20 P0 清浏览数据 | implemented | E8：真实设置勾选/时间范围/确认/逐项结果；普通与私密Cookie、localStorage、表单导航、历史与播放进度直接删除/保留断言。网页/播放缓存、站点权限、HTTP认证已有API接线。 | 缓存/权限/认证各类真实删除、表单清理后强停恢复、书签/下载/离线保留完整取证。Gecko不支持的时间筛选明确禁用，不能视为API已有该能力。 |
| B21 P1 DNS / HTTPS / 三方 Cookie | implemented | E42 补 DoH 自动/关/自定义、仅 HTTPS 默认关闭、第三方 Cookie 默认阻止。模拟器观察到 HTTPS 升级、Cookie 请求变化和 DoH 所选端点的 TLS 请求。 | DoH 有效 DNS 查询应答、自动模式的网络和地区条件、真机与站点兼容。 |
| B22 P2 分组 / 最近关闭 | pending | 普通 / 无痕分组不等同自定义标签组；只有短期撤销。 | 分组与最近关闭页面 / 列表恢复。 |
| B23 P2 阅读模式 | pending | 未接 `feature-readerview`。 | 入口、提取、主题 / 字体与返回原页。 |
| B24 P2 翻译 | pending | 未实现。 | 明确服务 / 隐私、交互与可访问性后实现并测。 |

### 广告 A1–A6

| ID / 优先级 | 状态 | 已有实现与证据 | 仍需完成 / 证明 |
| --- | --- | --- | --- |
| A1 P0 官方 uBO 首启 | implemented | 固定1.75.0、SHA、签名XPI、file install/private授权；E1。E7新增官方blocklist资源。E41调查了历史超时的三类原因，独立临时用户的在线和离线首装各一次真实拦截通过。 | 多轮首装、弱网、升级、真机和d3ward与桌面Firefox同版uBO同规则比较。 |
| A2 P0 盾牌 / 本页计数 | implemented | 实际 browserAction badge、未就绪 / `!` 刷新警告；本地 fixture 计数 >0。 | 零 / 变化数字、SPA / 导航 / 私密、UI / 准确性矩阵。 |
| A3 P0 站点 / 高级；元素拾取 P1 | implemented | 原版 popup-fenix / dashboard，站点白名单来源在 uBO。 | 关闭本站→刷新广告恢复专项、完整 Compose 外框 / 单一网站开关、元素拾取实际路径。 |
| A4 P0 全局 / 例外 | implemented | enable / disable / 原版 dashboard 例外。 | 可删除原生例外列表与切换 / 重启 / 私密回归。 |
| A5 P1 累计统计 | pending | 页面 badge 已有；没有累计总数 / 节省流量估算。 | 持久统计去重、页面累加口径、详情记录 logger 入口。 |
| A6 P0 冷启动不漏 | implemented | 真实 onReady / 最长 1.5 s gate；已装 uBO 的受控恢复页首广告没到 server，E1。E41用保留资料的临时用户实际冷启动，受控请求被拦截。 | 首装稳定性、超时放行分支、跨站 / 真机恢复矩阵；1.5s 超时允许加载不等同无条件零漏拦。 |

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
| P3 P1 按住后滑动换档 | implemented | E42 覆盖每 40 dp 切 1.5/2/2.5/3 倍、实时提示、松手和取消恢复速度，以及换档期间屏蔽 seek 和调节。 | 真机手感和时延、横竖屏中断和可访问性。 |
| P4 P0 进度条 | implemented | 4 / 8dp、自绘三层基础、16dp thumb、时间预览、松手 seek。 | 气泡、按下 thumb 动效、任意点直接 seek 和完整触摸 / 可访问性专项。 |
| P5 P0 横滑 seek | implemented | 10dp slop、默认整宽120s、松手 seek。 | 虚拟时钟状态机、横竖竞争 / 范围边界、中央目标 / 总时长 / 差值卡片。 |
| P6 P1 缩略图 | implemented | E19：统一官方FrameExtractor单视频ExoPlayer替代MMR/ImageReader示意，HTTP MP4/content URI/HLS TS/fMP4/AES/DASH真实帧；10秒/60张LRU、owned取消、UI目标时间/预览与失败无空框。JVM27/27、后端22/22及另整组UI5/5；同包常规135/135（881.462秒）。 | 其他provider/公网站点与codec/物理GPU兼容，构建/打包不扩大为ARM64运行。 |
| P7 P0 双击 / 连击 | implemented | 38 / 24 / 38分区、280ms / 80dp、260ms单击、750ms连击，E2 / E3 部分触摸。 | 全时间边界 / 中区开关、同侧连击与方向翻转状态机测试、弧形涟漪 / 文字与等价按钮。 |
| P8 P0 亮度 / 音量 | implemented | 左右半区、75%高度、窗口亮度 / STREAM_MUSIC；已加等价滑块语义。 | 真实上下滑、静音 / 系统限制、手势开关、触感和状态恢复。 |
| P9 P0 流缓存 | implemented | 独立 SimpleCache LRU、签名参数规则、清单走网络、auth 摘要分区、私密绕过；E2 / E3。  E32补HTTP5xx后显式缓存回拖恢复；最终选定13/15，零上游/起播失败仍保留，完整P9矩阵未闭合。| 真断网已看部分回拖、真实重签名 / 私密 / 缓存错误矩阵，8GB上限设置差异。 |
| P10 P0 预缓冲 | implemented | DefaultLoadControl、默认60s / 半数 min≥15s、起播1s / 重缓冲2.5s / 回退30s、DataSaver判断。 | 300s设置缺失；系统计费 / 白名单 / DataSaver设备专项。 |
| P11 P1 磁盘区段 | pending | E20–E25已串联普通HLS与当前period普通DASH的选轨、真实资源/SDK长度/原key闭合及磁盘UI。E26–E29保留MP4冷解码/metadata签名/raw时序与单视频AVC首批生产证据，原复杂edit差异和各失败未抵消。E33的0.5.18完整147/147及Release受控门通过。E36的0.5.19/build-06 AVC/AAC候选模块109/109、应用17/17及常规149/149（1114.743秒）通过，含内部seek字节漏项反例修正、1908次原生读取端点核对和en/ja真实PCM。E37补120秒AV画布/未来503恢复/同player清理，保留原UI0/2后优化查询，同包111/111模块、MP4 UI8/8通过；新151常规150过/1混合下载撤销失败，隔离通过不抵消。 | 整窗口跨period/动态/非独立DASH，LL/非独立/复杂HLS；MP4重排/复杂edit/其他codec/容器、原单视频内部seek、精确preroll/EOS和音轨切换；完整跨会话/未知资源/Range/鉴权/生命周期/清理/解码/几何及真机矩阵、更长片长内存/取消和新Release。不同轮次不得拼成全部验完。 |
| P12 P1 离线视频 | implemented | E4：真实DownloadManager/Service、HLS/DASH、独立非LRU缓存、停源音轨字幕和完成项强停后无网络UUID回放。E6：真实通知暂停/继续/取消及历史完成副本全片播放。 | 真机飞行模式 / 网络切换 / 后台 / 手机重启；下载进行中杀进程续传与重签名网站矩阵。 |
| P13 P1 超分 | implemented | E5：Anime4K S/M/L与FSR真实渲染、统计/暂停比较分割线、切档及旋转同一播放器。 | 指定 8 Gen2 级设备 540P动画→1080P均衡<12ms；完整GPU/生命周期兼容矩阵。 |
| P14 P1 超分 benchmark / 保护 | implemented | E5：真实720P片段、三档60真实帧和<16ms推荐/记忆代码；保护策略单测、实际shader错误直显。E30修45秒采样/10秒释放分期，JVM79/79与宿主GPU实际benchmark2/2；软件采样不足和原整组失败保留。 | benchmark UI完整流程、倍速/HDR/PiP及真实热状态/过载设备矩阵；不能以策略单测代替硬件行为。 |
| P15 P0 解码 | implemented | MediaCodecSelector auto / hw / sw、保位置重建、失败软解一次；E3-T软件重建实播。 | 硬解初始化失败路径受控复现、切换提示、各解码器 / 网络认证重建和无中断验证。 |
| P16 P0 续播 | implemented | Room摘要键、10s/退出、>5s且距结尾>15s、从头按钮、私密不读写；E2 / E3。 | 具体时间“已从12:34继续”提示、可关记忆设置、进程杀死 / 全站边界。 |
| P17 P0 PiP / Session / 焦点 | implemented | PiP3操作 / sourceRect / auto开关、MediaSession、焦点 / noisy配置。 | Home / Back / 多窗口 / 进程死亡、耳机 / 蓝牙 / 锁屏 / 来电 / 别App抢焦点真机矩阵。 |
| P18 P1 后台播放 | implemented | E42 覆盖默认关设置，普通视频开启后后台只播放音频，系统媒体通知可暂停、继续和返回，前台恢复画面，无痕不发布后台会话。 | 真机锁屏、音频焦点、耳机和蓝牙、各系统的前台服务限制。 |
| P19 P0 锁 / 比例 / 速度 / 方向 | implemented | 锁方向、自动视频宽高比 / 手动旋转、适应 / 裁剪 / 拉伸、0.5–3x列表；E3布局。 | 16:9 / 4:3（P1）、默认比例持久化、锁后单击只显示锁2s、侧栏 / 音调失败提示与完整设备矩阵。 |
| P20 P1 片头片尾 | implemented | E42 覆盖页面、目录和域名记忆，±5 秒、按当前位置设置、跳过提示、目录边界、片尾主动拖动和无痕隔离。 | 真实网站连续播放、流媒体时间轴差异和真机。 |
| P21 P1 字幕 / 音轨 | implemented | HLS双音轨、内嵌VTT、SRT/VTT/ASS纯文本、样式、内存 / 重建；E3-T。 | DASH字幕、多 provider 的完整系统SAF、复杂轨道 / 直播变化、字幕避让控制层。ASS复杂特效按规范不要求。 |
| P22 P1 双指缩放 | implemented | E42 覆盖 1–3 倍缩放、平移、接近适应或填充时吸附，以及旋转、画中画、暂停和超分画面。 | 真机手感、响应时延和设备兼容矩阵。 |
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
| D7 P1 存储条 | implemented | E42 以普通文件、暂存实际分配空间、离线分段和 Android 剩余空间验证合计与自动刷新，语义树可读数字。 | 真机、外置或不提供大小的目录以及 TalkBack 朗读。 |

## 第 2–6 / 8–10 / 12–14 节补充要求

这些条目保留第 11 节没有单独 ID 的明确要求；相同功能不会因分散在多节而漏验。

| ID / 来源 | 状态 | 要求、证据与下一步 |
| --- | --- | --- |
| ARCH-01 §2.1 / 3 | implemented | GeckoView / AC / Compose / Room / DataStore / OkHttp / 手写AppContainer已接。min26、target/compile37、release ARM64 / debug x86_64已构建；当前固定库版本须在最终发行核对稳定来源与完整依赖。 |
| ARCH-02 §3.1 | implemented | 现有模块包含superres；尚无app Navigator统一跳转接口。player→superres与downloads→offline存在feature依赖，跨模块跳转/依赖方向相对规范的偏差须处理或明确验收。 |
| ARCH-03 §3.2 | implemented | 独立PlayerActivity/BrowserStore、普通FGS与Media3离线DownloadService已有；后台播放PlayerService仍缺失。 |
| ARCH-04 §3.3 | implemented | 自有ensureBuiltIn bridge、native ports、all frame DOM、MSE原请求规则已有；uBO file install分离。需全生命周期与共享模块协议用例。 |
| ROUTE-01 §4 | implemented | NTP、tabs / downloads / history / bookmarks / settings / adblock与内部地址路由已有。播放器用不透明一次性token（保护私密，不应恢复明文src）；逐个验证内部route、外部VIEW/SEND/WEB_SEARCH与错误地址。 |
| UX-01 §1 / 5.1 | implemented | 无账号 / feed / 运营位、SplashScreen、原生首帧后建Gecko，符合现有API主线程创建约束；E40补首启可跳过引导，广告拦截说明按实际状态显示。启动耗时仍待；不能把“异步Application建Gecko”字面要求当作已满足，主线程API偏差须保留说明。 |
| UX-02 §5.2 | implemented | NTP共享输入、快捷方式编辑 / 添加 / 移除撤销、无痕隐藏已有。缺访问最多8项 / 系统语言预置核对、长按新 / 私密标签、P1语音与继续观看、P2壁纸。 |
| UX-03 §5.3 | implemented | 顶 / 底栏、焦点取消 / 全选、建议填入、进度条已有；E40补在线建议和内联域名高亮/退格/提交。缺eTLD+1 / 搜索词保持、适宜条件下剪贴板URL建议、长按复制 / 粘贴前往 / 分享、自定义搜索模板。 |
| UX-04 §5.3–5.4 | pending | E9已有三项站点权限面板、最终源码站点回归、真实系统允许/拒绝及近似定位专项证据；系统完整生命周期仍待。证书 / Cookie数据 / 本站uBO开关、外部intent:// / market:// / weixin://确认，拦弹窗Snackbar“始终允许”、文件选择及其他权限/页面提示仍须实现和验证。 |
| UX-05 §5.5 | implemented | 网格与撤销已有，E40补favicon接线；拖拽排序P1、多选标签、全部关闭还原及明确滑动阈值待补。 |
| UX-06 §5.7 / 9 | implemented | 原版uBO站点唯一真源已保留。缺AddonUpdater、默认额外Cookie提示列表、logger入口和累计数据；不得直接改uBO存储冒充已接入。 |
| UX-07 §5.8 | implemented | 接管 / fail回网页 / 短视频折叠已有；og:title优先与同页编号、缩略图 / 站点 / 复制按钮、全DRM / blob / 超时返回网页保证待补。 |
| UX-08 §5.9–5.10 | implemented | 普通下载改名、分享、打开和历史书签已有。E40 补网站图标，E42 补下载存储条及书签文件夹。文件管理器定位、相对日期和网站图标兼容仍待，Places 偏差待验收决定。 |
| UX-09 §5.12 | implemented | 私密FLAG_SECURE / 标签排除恢复 / 不写播放缓存进度已有；E40补常驻关闭通知/权限/后台关闭和Cookie清理。Android14+允许手动划掉通知；完整私密取证仍待。显式离线下载仍可用但先提示文件保留，不能把私密禁止离线当作完成。 |
| THEME-01 §6.1–6.2 | implemented | 品牌浅深色、动态色默认关、JSON token、无痕配色已有；E40补六预设选择器/保存/即时应用和深色调80。组件硬编码色审查、完整player token / semantic体系需补。 |
| THEME-02 §6.3–6.4 | implemented | 系统字体 / typography / 4dp栅格、关键44/56dp胶囊 / 48dp点击区部分实现。所有数字tnum、行高、圆角、0dp tonal、面板≤90%屏高、Snackbar4s / 撤销6s须截图与测量核对。 |
| THEME-03 §6.5–6.6 | implemented | 当前品牌图标 / monochrome与来源文件已有；E40补真实favicon及首字母fallback，内存上限8MB/无痕不进入普通缓存。Material Symbols Rounded outlined风格、selected filled、全站图标兼容和下载 / 播放进度视觉全审查仍待。 |
| PLAYER-01 §8.1 | implemented | 请求头清单 / 分片同传和跨来源认证隔离已E2 / E3验证。无改写清单；外部视频 / 本地 / 离线 / 直输询问各入口待最终完整验证。 |
| PLAYER-02 §8.2 | implemented | 竖屏16:9 / 标题 / 站点 / 按比例默认方向 / 手动全屏已有E3布局。缺竖屏本页其他视频列表、画质侧栏；P2选集 / 刘海画面延伸设置未做。 |
| PLAYER-03 §8.3 | implemented | 单击260、双击280/80、750连击、10dp slop在PlayerGestures集中。分区硬编码 / 所有手势常量统一、虚拟时钟边界测试、倍速失败Toast和P1换档 / 捏合仍缺。 |
| PLAYER-04 §8.4–8.5 | implemented | 内存缓冲 / 流缓存与默认预算已有，缩略图实现与受控验证见E19；不连续磁盘区段 / 完整离线矩阵、缓存上限8GB、预缓冲300s、流 / offline互不影响待补。 |
| PLAYER-05 §8.6–8.7 | implemented | 自动 / 硬 / 软和一次fallback、续播 / PiP已有。后台服务、按站点跳片头尾、16:9 / 4:3、P2 FFmpeg AC3/EAC3/DTS扩展、GL截图 / DLNA / 导出未做。 |
| PLAYER-06 §8.8 / 13 | implemented | E5保留14份GLSL及MIT来源，原生HookShader/Renderer/SurfaceView、统计/同帧比较/错误回退/旋转已有实测；4778e46又验证真实三档benchmark和取消/后台释放。快速硬件应用推荐、温控/HDR/PiP/驱动矩阵及物理性能门未完成。 |
| PLAYER-07 §8.9 | implemented | 清晰度parser / cache / limit已有E3-P；实际高分辨率回写、同源header隔离有证据，E4已有受控离线画质选择。特殊封装、unknown失败边界和公网矩阵继续保留。 |
| PLAYER-08 §8.10 | implemented | 20s提示 / retry、401/403刷新一次 / boundedfallback / release防重建有设备证据。断网恢复、manifest fallback、所有起播失败恢复网页与公网授权轮换仍待验收。 |
| DOWNLOAD-01 §10.1 | implemented | >4MB Range多线程、默认4/可1–8、默认并发3/可1–5、退避 / validator / checkpoint。手机重启、系统网络、前台服务限制与普通Bearer嗅探待验。 |
| DOWNLOAD-02 §10.1 | implemented | 默认Download/Miru / MediaStore，SAF指定目录、Content-Disposition→path→MIME、重名、blob/data响应流。API26–28、provider重命名冲突 / 撤销后文件真删除、体积 / 加密 / 权限拒绝待验。 |
| DOWNLOAD-03 §10.2–10.3 | implemented | Room普通下载 / segments schema已有。离线kind=video统一字段、thumbnail_path、speed / ETA、所有分类扩展名（含视频m3u8 / APK组合包）核对。 |
| DATA-01 §12.1 | implemented | 无账号 / 云同步 / 主动telemetry初始化、Gecko notifyTelemetryPrefChanged(false)，备份排除，E0静态依赖记录。未做网络抓包；Crash Reporter上传禁用与本机日志导出入口 / 全依赖无遥测需最终证明。 |
| DATA-02 §12.1 | implemented | 下载 / 播放凭据仅内存，重启Cookie bridge重取，progress / resolution摘要分区。对Room / cache / logs / Intent / saved-state做真实敏感值取证，签名URL和referrer隐私单独审查。 |
| DATA-03 §12.1 | implemented | E8全局清理包含播放进度/播放缓存，范围及实际删除证据有限；E40在线搜索建议可关闭/无痕不请求，默认关的退出清理仍未做。 |
| REUSE-01 §13–14 | implemented | 纯JS classifier、design/tokens.json、PlayerGestures集中常量已有。需扩充VAST共享用例、Swift token生成、完整手势 / shader / benchmark / Metal移植；不引入Cove片源协议。 |
| COPY-01 附录B | implemented | 已有中英strings、动词与单位大体一致。源码仍见直接“x / P / kind / ratio”等格式，需全可见文案 / 资源 / 单位与错误可操作性审查。 |

## 设置与默认值（§5.11 / 附录 A）

默认值已有静态配置不代表完整设置页已接入，也不代表即时生效已在所有系统状态验证。

| ID / 设置组 | 状态 | 基线事实与差异 |
| --- | --- | --- |
| SET-01 search.engine=google | implemented | 四内置引擎和DataStore，E40补在线建议开关/无痕禁用；E43补自定义 `%s` 模板校验（选中自定义时停用建议）与 NTP 快捷方式显示开关。缺继续观看/快捷方式数量精细设置及真机验证。 |
| SET-02 theme=system / dynamic=false / accent=ringoRed / toolbar=top | implemented | 默认值正确；E40补accent持久化和六预设选择器/动态色切换恢复；E43补可视化“顶部/底部”地址栏卡片（`SettingsCompletionIntegrationTest`）与 50–200% 网页文字缩放即时生效。真机和旋转/表单完整矩阵仍待。 |
| SET-03 blockThirdPartyCookies=true / httpsOnly=false | implemented | E42 中两项默认值与 DoH 模式已接入 UI、DataStore 和 Gecko。E43 补退出时清除浏览数据总开关、数据类型选择及主菜单“退出”清理流；完整退出强杀进程残留取证仍待。 |
| SET-04 adblock.enabled=true | implemented | 总开关保存并应用；首装稳定性 / 例外网站 / 高级控制和Cookie列表见A1–A4。 |
| SET-05 autoTakeover=false / webLongPressSpeed=true | implemented | DataStore与当前 / 新frame即时配置已验证。 |
| SET-06 doubleTapSeek=10 / longPressSpeed=2.0 / swipeRange=120 | implemented | 默认正确；UI seek5/10/15/30正确；hold只1.5/2/2.5/3，缺1.25起每0.25档；横滑范围缺90s。 |
| SET-07 defaultSpeed=1.0 / brightness=true / volume=true / centerPause=true / haptics=true | implemented | E43 已补默认播放倍速持久化与设置入口（含首播应用、`PlayerPreferencesDefaultsTest`）；手势开关系统状态与真机全面专项仍待。 |
| SET-08 resume=true / backgroundPlay=false / autoPip=true | implemented | E43 已补记住播放进度开关并持久化控制读写；P18 后台播放已实现，autoPiP全生命周期与真机焦点/耳机切换仍待。 |
| SET-09 videoFit=contain / decoder=auto | implemented | E43 已在播放器设置持久化默认画面比例与默认解码方式并在启动应用（`PlayerPreferencesDefaultsTest`）；硬解失败切换真实设备矩阵待验。 |
| SET-10 superRes=off / tier=balanced | implemented | PlayerPreferences默认OFF/BALANCED，DataStore保存；PlayerSettingsScreen有模式/档位/benchmark入口，E5已有受控渲染与界面证据。仍需最终版本全部设置持久化/重启和真实设备性能/保护矩阵；0.5.3 QUALITY benchmark全套超时保留。 |
| SET-11 streamLimitMb=1024 / prebufferSec=60 | implemented | 默认正确；E43 补 128 MB 档位，当前上限实际4096MB / 180s，仍缺8GB和300s可选档。 |
| SET-12 ask=true / maxConcurrent=3 / threads=4 | implemented | 默认与可调范围有代码 / 部分设备证据。 |
| SET-13 videoWifiOnly=false / deleteFileWithRecord=true | implemented | 默认删文件选项已有；E43 补“仅在 Wi-Fi 下下载视频”设置项与调度约束（`DownloadWifiOnlyTest`）。删除6s撤销与真机网络切换确认待验。 |
| SET-14 全开关即时生效 / 无保存按钮 | implemented | 现有DataStore开关遵循；所有新增设置和失败写入提示仍需验证。 |
| SET-15 站点设置 / 关于 | implemented | 关于版本 / 开发者 / 源码 / 许可 / uBO版本已有；E43 补“网站设置”总览与按站点/一键重置（`SettingsCompletionIntegrationTest`）。日志导出、本地完整许可证展示及全部新组件告知仍待。 |

### 同文档内的口径冲突与实现差异

| 编号 | 规范精确位置 | 审计结论 / 后续处理 |
| --- | --- | --- |
| SPEC-C01 长按生效时间 | §8.3 写“≥系统长按时长约400ms”，§11.4 P2 写“按住400ms内生效” | 近似系统阈值与严格400ms门不完全等价。当前使用系统timeout，不能推定满足严格门；后续需定时事件测量并明确采用可验证的判定常量，保留两条原文要求。§12.2“判定后<100ms”是另一段延迟，不可混为从按下计时。 |
| SPEC-C02 撤销窗口 | §5.5 关闭所有标签写“5秒内可全部恢复”，§6.6 含撤销Snackbar写“6秒” | 当前统一6秒，6秒窗口包含前5秒，但展示时长口径仍需明确。最终动效验收记录采用哪个展示值及原因，至少不得缩短5秒可恢复保证。 |
| SPEC-C03 Chip圆角 | §6.4 将Chip列在full胶囊组，§6.6 明确Chip为sm8 | 两个视觉值冲突，当前Material FilterChip不应同时被判两种形状全通过。优先明确组件专用§6.6口径并记录设计选择。 |
| SPEC-C04 历史 / 书签库与隐私 | §2.1 / §5.10 指定Places，§12.1 要移除Glean / Sync | 当前依赖解析发现Places引入不期望模块，已在PROGRESS解释SQLite偏差。功能CRUD通过不能视为指定存储技术要求也通过；最终须维护者认可偏差或找到符合两条约束的实现。 |
| SPEC-D01 长按档位 | §5.11 全局长按可设1.25–3.0/步0.25；§8.3 按住后左右滑换1.5/2/2.5/3 | 它们是设置值与手势快捷档两个不同要求，不是允许缺1.25 / 1.75等设置项的理由。 |
| SPEC-D02 缓存 / 预缓冲 | §5.11 8GB / 300s可选；§8.5默认1GB / 60s，附录A同默认 | 默认与范围不冲突。当前4GB / 180s上限是实现差异，需要修正，不能把默认正确当作范围符合。 |
| SPEC-D03 控制层隐藏 | §7.4 写4秒，PlayerScreen目前3秒 | 已在 E43 将播放态控制层自动隐藏改为 4 秒（锁屏 2 秒）；仍待虚拟时钟精确边界与真机交互专项验收。 |

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
| MB23 Chip背景 / 勾选展开 | implemented | E43 抽屉/筛选胶囊落地 `MiruFilterChip` 对号零宽展开与减动画淡入淡出（`ChipAndListMotionTest`）；真机帧数据待补。 |
| MB24 列表删除收高 / 淡出 / 上移 | implemented | E43 在下载、历史和书签列表接入条目删除与重排平滑位移（`ChipAndListMotionTest` / `DownloadMotionIntegrationTest`），下载页补日期分组；真机高频删除仍待。 |

| ID / §7.4 播放场景 | 状态 | 待完成或证据边界 |
| --- | --- | --- |
| MP01 控制层fade+上下8dp / 中央0.9 | implemented | E43 在 `PlayerControlsOverlay` 接入上下 8dp 位移与居中暂停按钮圆形底/0.9 缩放、`MiruMotion` 时长及减动画降级；完整真机帧追踪待补。 |
| MP02 播放4s自动隐藏 / 暂停不隐藏 | implemented | E43 已修正播放自动隐藏为 4 秒（暂停保留、锁屏 2 秒）；仍待虚拟时钟精确边界验证。 |
| MP03 播放暂停路径或交叉缩放 | pending | 当前直接切换Icon。 |
| MP04 双击弧形36% / 三箭头450ms | implemented | E43 接入 `DoubleTapArcRipple` 36% 弧形轮廓与三箭头 450ms 波动及累加数值，并修复 `PlayerTouch` 抬手到按下的双击间隔（`PlayerMotionIntegrationTest`）；真实触摸高刷手感仍待。 |
| MP05 长按顶中胶囊 / 800ms箭头 | implemented | E43 接入 `HoldSpeedPill` 顶部回弹胶囊与 800ms 流动三箭头及滑动换档提示；真机高刷手感仍待。 |
| MP06 横滑玻璃时间卡 / 差值 / 帧 | implemented | E43 接入 `SwipeSeekCard` 显示缩略图、目标时间、总时长与差值（无缩略图退化时间卡）；完整虚拟时钟与真机拖动响应待补。 |
| MP07 亮度音量200dp玻璃条 / 700ms | implemented | E43 接入 `LevelIndicatorBar`（200dp、图标、5dp 进度条、静音图标、700ms 自动清理与淡入淡出）；真机连续手势与系统音量受限回退待全测。 |
| MP08 进度4→8 / thumb0→16 / 气泡 | implemented | E43 在 `PlayerProgressBar` 接入拖动加粗与交互预览气泡（`PlayerMotionIntegrationTest`）；仍需真机全尺寸按压/无障碍专项。 |
| MP09 右侧320/200ms侧栏 | implemented | E43 将播放器五类弹窗统一替换为 `PlayerSidePanel`（360dp ≤85% 宽、320ms 滑入/200ms 滑出、减动画淡入淡出、`PlayerMotionIntegrationTest` 通过并截图核对）；真机中断与多分辨率Matrix待补。 |
| MP10 侧栏内容200ms交叉 | implemented | E43 所有设置/轨道/画质侧栏在 `PlayerSidePanel` 内运行；子内容切换细粒度 200ms 交叉过渡及中断专项待补。 |
| MP11 锁形变 / 单击仅锁2s | implemented | E43 实现锁屏下控制层隐去并 2 秒自动隐藏锁按钮；锁图标形变微动效与真机点击边界待补。 |
| MP12 左下玻璃Toast2.2s | implemented | E43 接入 `PlayerGlassToast` 统一播放器跳过片头/片尾及音量不可用提示，替换原系统 Toast。显示停留（2.5s vs 2.2s）与全真机文案专项待补。 |
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
| HAP01 各动作触感和系统开关 | implemented | 开关已有；E43 补齐长按 `LONG_PRESS`、双击与锁屏 `CONFIRM`/`CONTEXT_CLICK`、标签托盘横滑越线与下拉刷新阈值触感。物理马达手感与全部手势分支尊重系统关闭仍需真机核对。 |

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
| PERF08 ARM64 APK<110MB | verified（仅0.5.24体积和打包） | E43 最终 108,972,642 字节，SHA `a6e5fbf076dd293e81b27928150b7578f1dc72db5f8b5f8d459a6647bf8a85aa`，通过 1,626 项 payload、16 KB 对齐、同 Debug 证书、17 个 AArch64 库及 51 项 Gecko 资源验证。本版 ARM64 或相同 DEX 的 x86 Release 运行、完整 R8 验收和正式签名仍待。历史包见 E40–E42。 |
| PERF09 超分均衡<12ms | pending | 指定8Gen2级设备、540P动画→1080P、稳定统计含保护；与设置benchmark<16ms推荐门分别验。 |
| PRIV01 无账号 / Glean / Sync / FxA / 上传 | implemented | E0静态图与源码有证据；完整实际依赖、崩溃上传配置、抓包 / 日志取证、关于本机日志导出待补。 |
| PRIV02 Cookie / Authorization不落盘 | implemented | 普通下载 / playback / media models内存、分区摘要；需最终Room / prefs / savedstate / files / logs取证和重启恢复认证测试。 |
| PRIV03 私密全生命周期 | implemented | E0–E3局部历史 / Cookiejar / 缓存 / 进度 / 截图有证据；E10另补私密真实文本、关闭最后私密tab及旧token隔离两项；native context销毁 / process death / no残留全范围仍未证。 |
| ACC01 TalkBack完整任务 | pending | 打开网页→切换标签→下载→播放暂停→调倍速的实机读屏路径；仅contentDescription存在不够。 |
| ACC02 所有图标名称 / 48dp / 等价手势控件 | implemented | E43 将设置与播放器单选弹窗全部迁移至带 `heading()` / `selectableGroup()` / `Role.RadioButton` / `≥48dp` 行高的 `MiruChoiceDialog`，补齐标题/开关/滑块语义与 48dp 地址栏胶囊（`AccessibilityAuditIntegrationTest`）；完整界面树与所有等价手势仍待终审。 |
| VIS01 深浅×地址栏顶底×字体1.3x | implemented | E43 在模拟器 1.3x 字体 × 深浅模式 × 顶底地址栏实际截屏拼图检查设置、NTP 与撤销提示条，发现并修复 `MiruSnackbarHost` 1.3x 下“Tab closed”与“Undo”重叠问题；全页面（含播放器/下载/历史）及真机全组合矩阵仍待。 |
| VIS02 移除动画全流程 | pending | 运行系统scale=0的全操作链，静态循环 / 跟手 / 无输入阻塞。 |
| DEV01 模拟器+真机仪器测试 | implemented | API35/x86_64多批受控测试；完整0.5.3为77/79。0.5.4站点/混合下载7/7、真实系统3/3、生命周期2/2、JVM111项及lint0错误/22警告；固定APK常规87/87（641.35秒）。0.5.5/E10常规98/98（595.555秒）、fresh系统3/3（21.844秒）、普通/私密prompt11/11。0.5.6/E11完整102/106（1328.953秒）；0.5.7/E12完整103/106（883.116秒），余底栏页顶测试、图片复制后重开菜单和QUALITY超时；后续Core独立7/7及MIME上传13/13不抵消整套失败。0.5.8/E14上传整类15/15（170.368秒），新增capture生命周期独立3/3（65.015秒），同一测试APK随后整类18/18通过（199.276秒）；随后固定同包在新5600常规完整回归117/117（907.736秒）通过，含原上传8+MIME5+capture5；旧失败和排除类保留，不推定真机门。所有旧失败保留；两个强停重启类仍排除，系统权限类独立验证；0.5.8另启5602的真正首次启动1/1（36.811秒）及真实系统权限3/3（33.353秒）均通过。0.5.9/E15另有目录首批5/5及最终固定包跨模块单次49/49（602.313秒），原48/49失败保留；engine90/90含树10/10，仅API35/x86_64受控provider，尚无本版完整常规回归。0.5.11/E19同包常规135/135（881.462秒）已过，四个独立前置类及capability包排除不计通过，各专项和历史输入范围分别保留。release构建/打包仅按PERF08所列版本，0.5.8构建/打包见E14，0.5.9的R8/shrink构建与ARM64打包见E16，R8/ARM64运行仍待；API26–28 / 当前系统 / ARM64物理机仍缺。 0.5.12/E23最终固定同包常规139/139（941.713秒），含新增HLS磁盘UI4项；独立模块另41/41，JVM72/72及lint0/22。四个独立前置类和capability仍排除不计通过，完整真机/发布仍待。 0.5.13/E24同包常规142/142（1465.085秒），另最终模块59/59、UI7/7；代码83f2da9的R8/打包通过仍不代替ARM64/R8运行或完整真机验收。 0.5.14/E25固定同包常规143/143（954.367秒），另模块70/70、UI8/8与JVM74/74；cf1c52a的R8/ARM64打包通过仍不替代正式签名、ARM64/R8运行或完整规格验收。  0.5.18/E33固定同包147/147（1004.529秒）通过，JVM79/79、模块99/99、选定15/15分别归档；四个独立前置类及capability仍排除，物理机和完整规格未闭合。E42 的 0.5.23 固定同包常规复跑 201/201（1,136.611 秒），首轮失败保留，四个前置类与能力专项依然单列。E43 的 0.5.24 同源最终包常规回归 253/257（3,317.553 秒，4 项失败保留；两轮前序 252/257 与两组隔离 8/9 保留；同包专项 `Media+PlayerMotion+PlayerHoldSpeed` 9/9 等隔离通过不抵消整套失败）；四个独立前置类及 capability 包仍排除，真机/ARM64 运行仍待。 |
| SITE01 第11所有真实网站 / 登录 | pending | B1六站及R4媒体站点的兼容矩阵；需要真实账户时由人提供可用会话 / 完成认证，不猜测通过。 |
| REL01 发布构建 / AAB或按ABI拆分 | implemented | E43 固定 0.5.24 与当前工作树一致：R8 与资源缩减、Debug/Release Lint（0 错误/29 警告）、现存模块 JVM 315 通过/1 Windows 符号链接跳过，以及 108,972,642 字节 ARM64 开发包完整性通过。本版 ARM64 与完整 R8 运行、AAB 分发和正式签名仍待。历史检查点见 E40–E42，旧版 x86 同 DEX 受控运行见 E33/E39。 |
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
| M3 画质与离线 | implemented | 清晰度/字幕音轨、受控离线与超分渲染有E3/E4/E5，E19增加缩略图后端/完整UI、135项常规回归及打包；磁盘区段、完整离线/性能/真机兼容矩阵和阶段录屏仍未完成。 |
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
| R8 ABI体积 | verified（仅0.5.24打包） | E43/PERF08，108,972,642 字节，小于 110,000,000 字节，17 个 ARM64 ELF 库。ARM64 物理机、完整 R8 运行与正式签名仍待。 |
| R9 uBO / Gecko分发许可 | pending | 全部第三方 / 列表 / 嵌套库实际产物审查与人工确认；旧M0开源检查不能覆盖含uBO发行。 |
| R10 Google Play视频下载政策 | pending（条件项） | 仅在决定上架Play时需当时政策与站点限制核对；当前本地构建没有商店发布证据。保留此门，不主动上架。 |

## 下一阶段顺序与外部条件

以下服从 `AGENTS.md` 维护者 **2026-11-25** 的新方向（详见 [CHROMIUM-MIGRATION-PLAN.md](CHROMIUM-MIGRATION-PLAN.md)）。**既有 GeckoView 主线冻结于 `0.5.24`**，P11 磁盘缓存区段与 P2 项继续冻结。

> **历史证据边界（硬约束）**：上文表格与证据索引中的 `E0`–`E43` 全部属于已冻结的 **v1.0 GeckoView `0.5.24` 基线**。这些历史记录完整保留，但**绝不计作未来 Chromium 内核的验收通过**。凡依赖网页引擎、广告拦截、视频嗅探、下载 Cookie 桥接、站点权限/提示与单 ABI 体积的验收项，在切换至 Chromium 后必须按新内核重新取得实测证据并新增独立证据编号。

1. **Phase C0（Windows 本地已完成 + Android NDK 实装与模拟器实测通过，2026-11-26）**：
   - 架构解耦：已抽出 `:browser:engine-api` 与 `:core:native-core`，将原 Gecko/AC 实现收拢至 `:browser:engine-gecko`（所有 Mozilla 依赖收紧为 `implementation`），`:app/src/main`、`:browser:engine-api`、`:browser:adblock-rust`、`:browser:toolbar` 与全部 `:feature:*` 对 Gecko/AC 编译期零直接依赖。
   - 统一 Rust 核心与 NDK 交叉编译：扩展 `native/` Workspace（`miru-adblock` + `miru-media` + `miru-url` + 新增 `miru-storage` → 单一 `libmiru_core`），完成 `cargo test --release` **16/16** 通过；新增 `tools/build-rust-android.ps1` 调用本机 Android NDK `28.2.13676358` 编出双架构 `libmiru_core.so`（`arm64-v8a` `3,161,272` B ≈ `3.01 MiB`、`x86_64` `3,281,432` B ≈ `3.13 MiB`，`llvm-readelf -W -l` 验证 4 个 `PT_LOAD` 段均满足 Android 15 ≥ 16 KB 页对齐），并产出 `libmiru_core.a`（ARM64 `49.13 MiB`）供后续 Phase C2 Chromium 静态链接。
   - 生产模块实装与真进程验证：将 `:core:native-core` 接入 `:core:common`、`:feature:media-detect`、`:browser:engine-api`、`:feature:player` 与 `:app`；全工程本地 JVM 单测（直连 `miru_core.dll`）**325 通过 / 1 因 Windows 符号链接权限跳过 / 0 失败**，Debug 与 Release Lint 均为 **0 错误 / 27 警告**；`app-debug.apk` 确认包含双架构 `.so`；在 API 35 `x86_64` `emulator-5554` 实测 `MiruNativeCoreAndroidTest` **1/1** 通过（`0.12s`，进程内实载 `libmiru_core.so` 跑通 `adblock-rust` 5k 规则、真实 `segment00.ts`/`fixture.mp4` 解析、URL/Referrer/缓存键策略与 `miru-storage` 中文检索）及 `BrowserCoreIntegrationTest` **7/7** 通过（`150.277s`）。上述验证**仅证明原生 Rust 核心与既有解耦基线在 Android 15 模拟器正常执行，绝不计作 Chromium 内核验收通过**。
2. **Phase C1（去 Google 化 Chromium 159 编译流水线与最小浏览壳，2026-11-26）**：
   - **独立 Chromium C1 实测证据（不混用冻结 Gecko `E0`–`E43`）**：
     - 本地 WSL2 `Ubuntu-24.04` 编通去 Google 化 Chromium **`159.0.8154.33`** `//miru_engine:miru_chromium_engine_aar`（动态链接统一 Rust 核心 `libmiru_core.so`，`DT_SONAME: libmiru_core.so`，16 KB 页对齐），经 `tools/chromium-build/package-aar.py` 发布双 ABI（`arm64-v8a` + `x86_64`）本地 Maven AAR：`424,456,168 B`（SHA-256 `22d13420ca375c6d2aac1e818dba32635492dcfe07973c431515faca48569ed3`）。
     - Windows Gradle 消费预构建 AAR 编出 `:app:assembleChromiumRelease` ARM64 单 ABI 包：**`91,055,934 B`（`86.84 MiB` < `150 MB` 目标）**，SHA-256 `4b07251ebf5003b87ba5b7375e41d1c8dde07782c693caea81c13ed69a29cac6`；验证 APK 仅含 `lib/arm64-v8a/{libandroidx.graphics.path.so, libdatastore_shared_counter.so, libmiru_chromium.so, libmiru_core.so}`、零压缩启动资产 `assets/{icudtl.dat, miru_chromium.pak, snapshot_blob_64.bin}`，且 Mozilla/Gecko/uBO 与 GMS/Firebase/`play-services-*.properties` 条目均为空；`:app:lintChromiumDebug` 0 错误 / 145 警告，`:browser:engine-chromium:testDebugUnitTest` 与 `:app:assembleGeckoDebug` 保持可编可测。
     - Android 15 `x86_64` `emulator-5554` 实机运行与截图：`example.com` 真网页实时渲染截图人工核对通过；最终重打包后的 `ChromiumEngineIntegrationTest` 首轮整套运行为 `1/2`（`rendersPageWithChrome159UserAgentAndBlocksAdWithCosmeticHiding` 出现 1 次提交阶段元素隐藏时序竞争 `expected:<[none]> but was:<[block]>`），随即单独复测 `1/1`（`16.547s`）及整套复跑 `2/2`（`29.952s`）通过，验证 `Chrome/159.0` UA、多标签 `BrowsingLibrary` 历史、无痕 `OTRProfile` 隔离/销毁、广告拦截零服务端命中与元素隐藏。
   - **仍待完成**：连接实体 ARM64 手机实测本版 86.84 MiB 包的冷启动（`< 800 ms` 门）、滚动流畅度、抓包零 Google 外联审计与元素隐藏注入时机加固（归入 Phase C2）。
3. **Phase C2（原生 `adblock-rust` 全量接入与 Chromium C++ 网络层视频嗅探，2026-11-26）**：
   - **独立 Chromium C2 实测证据（不混用冻结 Gecko `E0`–`E43`）**：
     - 升级并重新打包双架构（`arm64-v8a` + `x86_64`）Chromium AAR：`424,524,310 B`（SHA-256 `c9e3e9415258244f12ff26762ddd552bd7165ab771671e09cd13e548b2d898c6`），新增 `MiruSniffingURLLoaderClient` C++ 零正文拷贝媒体分类、请求头内存捕获（`Referer`/`Origin`/`User-Agent`/`Authorization`）、`OTRProfile` 隔离 `GetCookiesForUrl`、`DOMContentLoaded` 外观注入加固与 128-bit `bridge_nonce_` 多帧隔离世界桥。
     - 随包内置 4 份离线默认过滤规则并后台优先构建/复用 FlatBuffers 快照（`engine.dat`）；接通 `NativeAdblockSheet`、过滤列表开关/自定义规则页、`SettingsScreen` `nativeAdblock` 文案适配，以及 `ChromiumMediaManager`（网络头与子框架 DOM 元数据/进度融合、UI 线程调起 `prepareMedia`、`restoreWebMedia` 与网页长按倍速）。
     - 本地 JVM 单测 **25/25** 通过（`:browser:engine-chromium:testDebugUnitTest` 4/4、`:browser:adblock-rust:test` 3/3、`:feature:media-detect:testDebugUnitTest` 18/18）；`:app:lintChromiumDebug` **0 错误 / 145 警告**；`:app:assembleGeckoDebug` 保持通过；`:app:assembleChromiumRelease` ARM64 单 ABI 包 **`91,101,159 B`（`86.88 MiB` < `150 MB`）**、SHA-256 `a10504831c169733cca2d1d93a11045e40eebe5a5488ee99c40f1ca50462c329`，仅含 `arm64-v8a` 与零 Gecko/uBO/GMS 条目，4 份内置规则文件与未压缩核心资产核对通过。
     - Android 15 `x86_64` `emulator-5554` 端到端实测 `ChromiumEngineIntegrationTest,ChromiumPhaseC2IntegrationTest` **`4/4` 通过**（`288.352 s`）：覆盖冷启动默认规则零请求命中与外观隐藏、本站白名单切换与规则恢复、元素拾取激活与自定义选择器规则生效、子 iframe `/embed` 视频 C++ 嗅探与 `Referer` 保留、`prepareMedia` 断点接管与会话 Cookie 带出、以及无痕视频与普通 Profile Cookie 严格隔离和永久清理；另实机截屏核对 `example.com` 上 `NativeAdblockSheet` 渲染正常。
   - **仍待完成（不夸大为已通过）**：真实公网视频站点兼容（HLS/DASH/401-403 刷新重试）、在线 `d3ward` 拦截率实测、以及物理 ARM64 真机冷启动与高刷日常使用冒烟待补。
4. **Phase C3（功能对齐、Rust 热点深化与去 Gecko 收口；Batch C3.1 已完成，2026-11-28）**：
   - **独立 Chromium C3.1 实测证据（不混用冻结 Gecko `E0`–`E43`）**：
     - 双架构（`arm64-v8a` + `x86_64`）Chromium AAR 升级：`424,592,972 B`（SHA-256 `f758514e699fcea3cbd9b4a9239f9dc35abe246ab2d8b013fadfac15c79e41cd`），接入 `MiruDownloadManagerDelegate`（含 `SetDownloadManager`/`Shutdown` 生命周期绑定，修复 OTR teardown `DownloadManagerImpl::Shutdown` UAF）、`MiruHttpsOnlyNavigationThrottle`、`MiruContentBrowserClient::{ConfigureNetworkContextParams, ApplySecuritySettings, ClearBrowsingData}` 与 `MiruTab::{DidChangeVisibleSecurityState, CaptureThumbnail}`。
     - Kotlin `:browser:engine-chromium` 落地 `ChromiumDownloadBridge`、`ChromiumSecurityHelper`、`ChromiumSessionStore` 与 `ChromiumSurfaceHost` 单次挂载 `TabOverscrollProxy`（修复 `RenderViewHostChanged` -> `OverscrollRefresh::Reset()` 悬空指针 UAF）。
     - 本地 JVM 单测 **28/28** 通过；`:app:lintChromiumDebug` **0 错误 / 145 警告**；`:app:assembleGeckoDebug` 保持通过；`:app:assembleChromiumRelease` ARM64 单 ABI 包 **`91,139,367 B`（`86.92 MiB` < `150 MB`）**、SHA-256 `de17e1ceda61e37a2d4d78dbc8aecbf2ff4ecc13fceb20112c0ee116b568e601`、仅含 `arm64-v8a` 与零 Gecko/uBO/XPCOM/GMS/Firebase/PlayServices 条目。
     - Android 15 `x86_64` `emulator-5554` 运行 `ChromiumEngineIntegrationTest,ChromiumPhaseC2IntegrationTest,ChromiumPhaseC3Batch1IntegrationTest,CookieBridgeIntegrationTest` **`9/9` 通过**（`20.226 s`，0 崩溃）：验证 HTTP + 隔离 Cookie 下载、403 Cookie 轮换重试、OTR 下载与 Cookie 严格隔离、`data:` 流下载、`BrowsingDataRemover` 清除 Cookie + `localStorage`、仅普通标签 `undoClose()`、HTTPS-Only 升级及叶子 X.509 `CertificateDetails`；保留的真实 `BrowserSecurityIntegrationTest` / 物理真机矩阵与 Batch C3.2–C3.4（网页提示/弹窗/外链/长按菜单、B13 站点权限/上传/通知/表单控件、Rust 热点深化及移除 `:browser:engine-gecko`）继续推进。
   - **后续 Batch C3.2–C3.4 & Phase C4（对等收口与正式发布）**：完成网页提示/弹窗/外部 App 确认/长按上下文菜单、B13 权限/上传/客户端证书/Web 通知/表单控件、按实测推进 `miru-storage` / `miru-download`、归档移除 GeckoView 后端，以及 ARM64 真机日常/高刷/超分验收、单 ABI `< 150 MB` 体积门、上游安全 rebase 流程、第三方分发许可复核与正式签名。
5. **M5 iOS**：待 Android Chromium 路线完成 Phase C4 逐门证据后再启动。iOS 不是当前 Windows 能完成编译验收的平台，但仍在规范范围中；不得把未编译/未运行写成验收通过。

本次环境只确认 Windows、Android SDK / JDK 与可启动的 API 35 模拟器能力；审计时 ADB 没有已连接物理手机，未发现 `xcodebuild` / `swift` 命令，没有 iOS 工程和正式签名配置。**新增外部条件**：Phase C1 起需要可用的 **Linux x86_64 构建机或 CI 环境**（Ubuntu 22.04/24.04，≥ 64 GB 内存、推荐 128 GB，≥ 250 GB NVMe 固态硬盘）用于编译 Chromium 内核并产出预构建 `.aar`。连同真实 Android ARM64 设备、90/120 Hz 与指定 SoC 超分性能、登录验证账户、macOS / Apple SDK / iOS 设备、维护者正式签名与分发许可人工确认，均属于外部验收条件：不妨碍在 Windows 本地推进 Phase C0 引擎解耦与纯逻辑验证，也不能凭缺少外部条件就将完整目标标记完成。

## 更新规则

每批结束时记录：提交hash、确切源版本、设备 / ABI / OS、执行命令、测试日志、失败 / 排除项、截图 / 录屏、APK SHA与签名。将证据连回本矩阵的具体行，只提升被新证据完整覆盖的要求；不得把代码存在、构建通过、测试被排除或模拟器局部成功提升成全量验收。
