# B13 文件上传（0.5.6 开发批次）

此记录对应 `B13-FILE-UPLOAD-PLAN.md`。首批八项真实系统选择器测试已通过，完整 B13 与上传验收门仍未关闭。
原计划中的“尚未实现”是方案审查时的历史状态；本批已有生产实现及真实系统选择器测试。
0.5.5 的 98/98 常规回归、ARM64 包和摘要仍单独保留，不套用于本批源码。

## 实现与依赖边界

- 固定 Android Components 158.0.2 / Gecko 158.0.20261021001945；实际解析 AndroidX Activity 1.13.0。
- Java 兼容层调用实际 AAR 中的 Kotlin internal JVM getter 和 Gecko package ABI，属于固定版本内部兼容层。
  不称为官方公开 AC API；升级必须重新核对。只替换 SINGLE / MULTIPLE File 的 native delegate 分支，其他提示保留原 delegate。
- IO 阶段读取系统 provider、保留原 DISPLAY_NAME、按请求与文件分别暂存，避免同批同名覆盖；native confirm/dismiss 在 Main 完成一次。
- 最终 canonical / 可读文件检查在 IO 产生绑定真实 FileRecord 的一次性 capability；Main 只核验所有者和该 capability，
  仍经原共享 single/multiple 回调交付。任意未经准备的 URI 不能绕过 IO。provider 取消、stream-close 取消及 capture grant 撤销也从 IO 执行。
- 非 exported 的 `UploadPickerActivity` 接收原始系统结果，Intent 只含随机进程内 channel。
  URI 不进入 MainActivity 的 ActivityResultRegistry 或其默认 saved-state；成功复制交付后结束 receiver，使 Android 释放临时 grant。
- 成功文件按真实文档持有租约。顶层原生 SAME_DOCUMENT 事件区分 hash/history 与新文档，
  相应两项精确 Gecko 资源修改与归档证据见 `B13-DOCUMENT-LIFECYCLE-VALIDATION.md`。
- staging / capture 专属目录采用 NOFOLLOW 与边界检查、可取消复制、实际字节预算及启动遗留清扫。
  不把上传放进持久下载队列，不取得持久 URI grant。

固定 SDK 回调线程约定见计划文档；Activity 1.13.0 源码/实际 AAR 的摘要和 saved-state 检查见
`artifacts/b13-upload-lifecycle/activity-result-1.13.0-20261026-013713/AUDIT.md`。
静态接线不等于已完成 Bundle、磁盘、私密上下文及进程死亡取证。

## 已运行的构建与失败历史

所有设备均为受控 API35 / x86_64 模拟器，合成 DocumentsProvider 内容经真实 DocumentsUI 选择。
测试不伪造 PromptRequest、系统 ActivityResult、网页 FileList 或 native callback；网页将 File 元数据和实际字节 POST 到 loopback 服务。

| 记录（均在 `artifacts/browser-core/`） | 结果与范围 |
| --- | --- |
| `056-upload-first-build.log` | 首次构建失败：Activity 1.13 权限方法数组签名不匹配；当轮 JVM 报告 123 项通过。 |
| `056-upload-repaired-build.log` | APK 编译成功，lint 因 Compose 中 LocalContext 资源读取报 1 error；已改用 stringResource。 |
| `056-upload-lint-repaired-build.log` | 构建成功，lint 0 error / 22 warnings。 |
| `056-upload-first-device.log` | 4 项 / 5 个失败记录：旧 monitor 没匹配 MIME，旧 Back 驱动失败造成后续 slot 未排空。保留原日志。 |
| `056-upload-receiver-build.log` | 主 APK、测试 APK、engine JVM、lint 成功，lint 0 error / 22 warnings。 |
| `056-upload-receiver-device.log` | 5 项 3 通过 / 2 失败，150.367 s。单文件元数据/字节、取消保留旧选择、hash 同文档请求通过；重建与多选驱动失败。 |
| `056-upload-driver-build.log` | 只重编测试 APK 成功，32 s。 |
| `056-upload-driver-device.log` | 5 项 3 通过 / 2 失败，76.831 s。真实 STOPPED host 重建、单文件含临时 grant 释放、系统/App 取消通过；首个 fixture 页面未就绪及多选完成判定失败。 |
| `056-upload-hash-ready-device.log` | 同一主/测试 APK、已初始化实例上 hash 专项 1/1，通过 14.846 s。保留首轮页面就绪失败，不据隔离通过断言根因已解决。 |
| `056-upload-io-lifecycle-build.log` | 主/测试/R8 release 构建成功，3m18s；Android JVM 113 项通过，lint 0 error / 22 warnings。未包含独立 JVM common 的 10 项。 |
| `056-upload-io-lifecycle-device.log` | 8 项 4 通过 / 4 失败，115.817 s。关闭 owner 后迟到结果的 grant 未释放，影响后续私密/新文档；多选实际仅选中 1 项。 |
| `056-upload-late-grant-repro-device.log` | 旧固定 APK 关 owner 用例隔离复现失败，41.35 s；当轮 in-run dumpsys 观察摘录保留在 `056-upload-late-grant-observation.md`。 |
| `056-upload-grant-owner-build.log` | receiver 修复后的主/测试/R8 release 与 lint 成功，3m46s；含 common 的 JVM 123/123，lint 0 error / 22 warnings，逐报告摘要见同名前缀 `build-result.json`。 |
| `056-upload-grant-owner-device.log` | 完整文件专项 **8/8 通过，112.02 s**；含同名多选两份实际字节、重建、取消、hash、新文档、私密关 owner 和旧结果隔离。 |
| `056-full-device.log` | 首轮常规 **85/106 通过、21 项失败，984.245 s**。11 项页面提示被 Java default 方法默认取消、8 项上传因旧测试遗留私密标签而前置失败；另图片复制与 QUALITY benchmark 失败。所有逐项 status 保留，最终聚合失败长栈被 instrumentation 截断；摘要见 `056-full-result.json`。 |
| `056-upload-forwarding-build.log` | 全部25个显式转发与每方法fixture清理后的主/测试/R8 release构建通过，5m37s，JVM123项及lint0错误/22警告；实际javap确认25个编译转发方法及Kotlin继承关系。 |
| `056-forwarding-adblock-device.log` | 全新只读5596、安装前无Miru包，真实官方uBO首装/拦截/开关/popup **1/1，41.44 s**。不是多轮稳定性或在线更新验收。 |
| `056-runtime-fresh-device.log` | 同一新实例，四项Android权限均为未授予的直接前置；系统实际拒绝/音视频允许/近似位置 **3/3，60.882 s**，没有pm grant。 |
| `056-forwarding-targeted-device.log` | 47项中**39通过/8失败，548.342 s**。包括全部11项普通/私密页面提示、旧图片复制及5类fixture测试通过；文件8项只在新增uBO READY前置失败，未进入文件选择。原失败保留，不能拼成47/47。 |
| `056-upload-startup-independent-build.log` | 只重编测试APK，去除无关uBO前置并增加读/写grant分别释放断言，1m19s。生产APK未改。 |
| `056-upload-startup-independent-device.log` | 最终主APK上的文件专项 **8/8，81.741 s**。真实页面/字节/数量断言不变，临时READ和WRITE均单独为DENIED。运行中安装状态另记runtime日志，不能由此推定所有冷启动时序已通过。 |
| `056-committed-full-device.log` | 提交`56a22a1`及最终固定APK的完整常规 **102/106通过，4失败，1328.953 s**。上传8项与普通/私密提示11项全部通过；剩余uBO READY超时、两项GEO重新询问、QUALITY benchmark超时。输入/逐项摘要见`056-committed-full-input.json`与`056-committed-full-result.json`。 |
| `056-restored-adblock-device.log` | 同提交旧APK在已安装profile上隔离复跑 **1/1，34.377 s**；日志有实际Native ready及setup完成。它不能抵消完整回归的READY超时，也不足以唯一归因为某一时序。 |

receiver修复首个八项通过轮输入 `056-upload-grant-owner-input.json` 固定基线 `01fafeb` 加本批源码，主 APK SHA-256
`b102bdda43c0fa3d9c7993a30824677f80f7cf18223fda06208d07fbbb80bcd2`，测试 APK SHA-256
`e1fdf57980ccbde78d535effd1344d1293b6dfb58f541ced4b0c37df8b1714a4`，设备 `emulator-5594`。
此前各轮 input 与失败日志均保留；所列 APK 摘要只对应当轮构建，不表示仍保存了每轮二进制。
修正驱动后的单项成功不能覆盖完整套件失败。后续 receiver 修复轮另保存独立不可覆盖的二进制快照。

后续显式转发修复的最终生产APK保存在 `056-forwarding-binaries/debug.apk`，SHA-256
`1cc9c747457a26b784902641c1e46d2e4b4d5904c39fe7792af5979ab3ee45f4`；
最终测试APK另存 `androidTest-startup-independent.apk`，SHA-256
`3d3027bd5a1505e991caf9047cccfda535cc67d2a37d9eb5fc7c4e2b4eb6debc`。
此前含uBO前置的测试APK也保留，不能将该47项日志标为最终测试APK的结果。
生产与测试源码已提交`56a22a18f9778773f1df58f6082a171d33da5aa6`；
`056-committed-source-map.json`记录构建后对应的干净源码提交及不可覆盖二进制映射。

中间测试曾增加uBO READY前置，但浏览器在1.5秒有界等待后允许加载，合法上传也必须可用；该耦合已移除。
页面仍须在原30秒内完成真实GET / DOM / 两帧rAF beacon；不以增加页面超时解决首轮失败。
实际uBO状态单独记录，不伪造ready、不改保护策略；A1/A6完整冷启动、安装及漏拦门继续保留。

旧代码关闭 owner 时提前结束原 receiver，真实系统随后把 URI grant 挂到了已结束的 ActivityRecord，
当轮授权断言因此失败。修复后原网页立即作废并清理文件，只保留已启动系统通道的 UUID tombstone 和弱 host；
receiver 等旧结果返回再结束。配置重建只重新挂接，不重新 launch。八项通过前置确认无合成 provider grant，
并在真实返回后确认原 receiver 消失、源 URI READ 权限为 DENIED，再让下一 owner 使用自己的选择器上传。
没有通过全局 revoke、pm grant/clear 或伪造结果规避失败。
单私密 owner 用例也不覆盖普通/私密并存或所有 context 切换。

## 常规回归暴露的问题

旧 adapter 的 `GeckoSession.PromptDelegate by original` 没有为 Java default 方法生成转发。
实际 Debug/release javap 只见 `onFilePrompt`，其他方法继承 Gecko 的默认取消行为，导致真实文本、alert、认证等全类失败。
修复改为 Java forwarding base 显式转发固定接口全部 25 个 callback，Kotlin 仅覆盖 File。
编译前后检查和固定接口源摘要见
`artifacts/b13-upload-lifecycle/prompt-delegation-20261026-165534/`，不能把仅源码覆盖检查算成运行回归通过。

BrowserCore、BrowserUi、ContextImagePolicy 等旧测试创建私密标签后未在所有路径关闭；首轮到上传前置时已有其他方法的残留。
测试改为每方法在 restoration 完成后捕获原标签/选择，只关闭本方法新增 ID、恢复原选择并验证原标签仍在；
PrivateFormCookie 也移除无前置保护的全关私密操作。没有通过删除上传的隐私前置或清除用户标签来取得绿色结果。

DocumentsUI 多选驱动依据实际 advertised ACTION_LONG_CLICK，而非 isLongClickable 布尔属性；
它必须先观察两行选中状态从一项变成两项，再提交并检查原名/字节。
参考固定 AOSP [AccessibilityEventRouter](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/aml_doc_351113060/src/com/android/documentsui/dirlist/AccessibilityEventRouter.java#79)
和 [DirectoryFragment](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/aml_doc_351113060/src/com/android/documentsui/dirlist/DirectoryFragment.java#1023)；
该源码标签不是模拟器预装 APK 的字节一致性声明，设备上的实际选择状态和上传字节才是运行证据。

## ARM64开发包

显式转发后的原AGP release为111,158,173字节，SHA-256
`212aca35e829fe398fa78b0930c33e287d67672809f81aada1d8a36ee5ecd0a4`。
最终 `artifacts/releases/Miru-0.5.6-arm64.apk` 为 **108,007,656字节**，SHA-256
`f874d3192cf7bc057eabd58b259b0d296d51b3e738549280c5ba836b98023dbb`。

同名 `.verification-20261026-093251-4dcff025/pipeline.json` 记录全部8步成功：1518项payload逐字节保留、
SDK对齐、v2/v3签名和原Android Debug证书一致，证书SHA
`5aa1a00e59b8638298dbddcc5a61053ca245249136edb67199e0c9a3e4cb1f84`。
`056-release-native-verification.json` 另核验17个ELF64/AArch64库；
`056-release-omni-verification.json` 为PASSED：4077原项不变、GEO与导航各1项精确修改、5项官方新增，原last-modified索引不变。
omni SHA为 `f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`。

最终测试源再经 `056-final-lint.log` 验证，0 error / 22 warnings。
这是Debug签名开发包的构建与打包证明，ARM64运行、R8运行和正式签名仍未验收；x86_64 Debug设备专项不替代这些门。

## MIME扩展验收（生产仍为041ec81 / 0.5.7）

新增五项真实系统筛选场景，不改生产MIME策略。测试provider增加固定10字节`opaque.bin`，含00/ff/80，
以免混合TXT/PNG被错误的全类型放行测成绿色。

| HTML accept | 实际选择与过滤证据 |
| --- | --- |
| 无accept属性 | 系统真实多选TXT、PNG、BIN，三份名称/类型/大小与各自POST字节一致。 |
| text/plain | 真实TXT上传；PNG/BIN在完整列表中隐藏或实际不可选。 |
| text/plain,image/png | 真实多选TXT+PNG，BIN负例不可选，二份实际字节校验。 |
| image/* | 真实PNG上传，TXT/BIN负例不可选。 |
| .png | 真实PNG上传，TXT/BIN负例不可选。 |

首轮`057-mime-viewport-device.log`为17/19（264.645秒）：Core6、原上传8及NONE/TXT/MIXED通过，
两个图片场景在root文件行识别阶段失败。固定AOSP [GridPhotoHolder](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/aml_doc_351113060/src/com/android/documentsui/dirlist/GridPhotoHolder.java#195)
图片tile仅把名称设到contentDescription；驱动现统一识别dir_list内真实document row，支持文本/描述并按实际行去重。
没有降低同名两份、文件数、过滤或网络字节断言。

`057-mime-grid-behavior-build.log`重编测试/lint成功；PNG两项在同批定向运行中通过。
最终`057-mime-complete-device.log`上传整类 **13/13（146.829秒）**，包含全部原8项；
本轮主APK为0.5.7的`057-retained-ready-binaries/debug.apk`，SHA-256
`d368d9c6196631327ca2cead58634843c08903f9bf794c75ea82ee2793ae0c7b`；
测试APK另存`057-grid-behavior-binaries/androidTest.apk`，精确摘要见`057-grid-behavior-input.json`及`057-mime-complete-input.json`。
这些是扩展测试包的直接证据，不把旧106项回归改写成新测试包全绿；capture/目录与完整公网站点矩阵仍待。

## 尚未完成的门

- 已覆盖五种accept的受控TXT/PNG/BIN系统筛选；其他MIME/提供方、公网站点与跨请求同名矩阵仍待。
- 拍照/录像及普通拍照review取消、录像review关owner迟到结果、录制中重建见`B13-CAPTURE-VALIDATION.md`，最新上传整类18/18（199.276秒）；录音、其他方向/handler、其余拒绝/取消/生命周期及目录二次确认/relativePath仍待。
- 私密成功/取消、单 owner 与最后 context 关闭、IO 取消、导航/关 tab/换 session 后的旧结果隔离。
- Bundle / saved-state / Room / preferences / session / logs / 专属 cache 与 URI grant 的直接取证；进程死亡和遗留清扫。
- 历史完整回归失败及当前三项问题的后续证据；其余页面提示（选项/日期/颜色等）、权限/站点矩阵仍待，已通过的页面提示不替代这些缺项。
- 初始 about:blank / opener document.write / popup / WebExtension 新 session 的真实文档握手及文件交付。
  固定 Progress 模块明确不保证最初 blank 的 location 事件，不能把未收到事件的 generation 0 当成有效文档。
- 本批R8实际运行、ARM64实际运行、正式签名及真机矩阵；已通过的构建/体积/打包不替代这些门。

以上仍按 `ACCEPTANCE.md` 的 B13、PRIV、DEV、REL 各门逐项记录，不将安全取消当作该功能支持。
