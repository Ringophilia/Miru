# B13 网页提示首批（0.5.5 已提交；完整矩阵未验收）

本批从固定Android Components 158.0.2的PromptRequest与BrowserStore契约接入网页提示，
不是整个B13验收记录。前一权限批次和0.5.4产物见B13-PERMISSIONS-VALIDATION.md；
后续源码不能套用0.5.4 APK或87项回归结果。

## 范围与约束

- 本批支持alert、confirm、prompt、beforeunload、POST重发确认及HTTP认证。
  UI继续使用ComponentActivity与Compose底部面板，原生请求/callback仅在engine协调器内保留；
  表单和密码草稿只在内存，不进入SavedState、日志或持久库，不提供保存登录。
- 请求必须绑定发起时的真实tab/session/原生文档与请求UID；切页、关页、session替换、崩溃及
  引擎结束/更新提示时明确终结或转移，旧UI token不能回答新提示。Activity重建本身不替换文档或取消请求。
- beforeunload需要跨导航尝试保留，Stay走onStay，Repost取消走onDismiss；
  不能直接复用权限协调器“导航命令即作废”的规则，也不能把尚未接受的目标URL当作发起来源。
- 明确区分可信来源与网页提供的标题/正文。认证显示真实来源、HOST/PROXY、跨源和上次失败信息；
  原密码不投影给UI，重新输入后只交回原生认证callback。
- 未实现的文件/目录、选择、日期颜色、登录/地址/卡片保存等提示必须取消并消费，
  CertificateRequest用onComplete(null)，Popup/Redirect用Deny；安全取消不算已实现这些功能。
- 新旁听observer必须对两种权限请求保持中立，避免再次触发AC的默认reject。

## 固定接口证据

本机固定源码缓存`C:/Users/tocml/AppData/Local/Temp/miru-ac-158/`：
concept-engine的prompt/PromptRequest.kt、browser-state的ContentAction与EngineObserver、
feature-prompts的PromptMiddleware已审查。Alert/Text/Confirm的“继续显示”Boolean被Gecko忽略，
若显示停止提示选项，需按原文档单独保存内存抑制状态。
PromptRequest的uid与shouldDismissOnLoad不参与各data class结构equals，不能据结构相等防重。
当前批只有BeforeUnload的shouldDismissOnLoad=false；Repost为默认true。
CertificateRequest是非Dismissible的类型，其Gecko完成回调需要应用自己防重。

BrowserController已有会话的navigate将地址保持在已接受的native location，
无会话首次link才预发布目标，避免Stay后地址误显示为未到达的目的地。
这项变化必须由真实beforeunload的Stay/Leave、标题/地址/HTTP文档请求数和普通导航回归证明。

## 待验证

首轮`055-prompts-first-build.log`构建成功（3分12秒），Debug主/测试APK及lint已生成，
lint为0错误、22警告。该轮只运行Android模块的`testDebugUnitTest`；不能把已有core JVM XML当作本轮新执行。
`055-prompts-first-input.json`记录API35/x86_64只读5592实例和安装前无Miru包，
主APK SHA-256 `6993871f00e8c90dc59be83fe2aa5400f2df270b92f0c897bb60b270cb795be4`，
测试APK `ee3f21519f223fa7fbe0b3cc340ff1df2a3803bb405c5f4902bc5da4a85b95ea`。

`055-prompts-first-device.log`首个真实JS用例**1项失败，28.172秒**：
可信触摸之后，等待真实ALERT面板超过原20秒。没有放宽超时或使用构造请求绕过。
固定AC的`GeckoPromptDelegate`在notifyObservers的lambda内分别构造各observer的请求wrapper，
但completion闭包在lambda外共享；因此只按PromptRequest对象身份关联同步capture与AC store请求不成立。
修复需要按原生completion闭包的引用身份关联，同时保留同步捕获的tab/session/document归属，
区分store wrapper UID、替换后的旧UI token与已完成原生callback；不能依据结构equals或迟到store状态重建来源。
固定源码副本及SHA见`artifacts/browser-core/055-source-audit/`。

另外，固定API除Authentication外没有这些提示的frame URI。UI只将捕获的顶层页标为当前页面，
不把它投影成已经验证的发起frame来源；Authentication才显示请求URI的origin。

## 共享原生回调身份修复后的专项

`055-prompts-native-identity-build.log`构建成功（2分15秒），同时执行`test`与`testDebugUnitTest`，
JVM XML合计111项、0失败/错误/跳过；Debug主/测试APK及lint成功。
`055-prompts-native-identity-input.json`固定本轮二进制与协调器源码hash，
`055-prompts-native-identity-device.log`整类**7/7通过，33.102秒**。
真实JS返回值、Activity重建同token/同文档、导航/关页旧token无效、
beforeunload Stay/Leave保留或实际改变地址/标题/HTTP请求数、
POST取消不重发/确认只增加一次、HTTP401成功/独立端口取消均取得本轮证据。
首轮ALERT超时失败仍保留，不能用后续通过抹去。

随后另补两项UI修正：固定Gecko将hasShownManyDialogs写死为false，
脚本提示应直接提供阻止后续提示选项；取消文本输入也要应用选中的策略。
每个提示用`key(token)`持有独立BottomSheet状态，避免真实Scrim关闭隐藏后被下一项沿用。
新增连续提示抑制/新文档恢复和真实Scrim关闭后下一项可见的两项设备用例，整类变为9项。

`055-prompts-dialog-build.log`记录测试缺少`click`扩展import的编译错误及同轮lint分析异常，
补齐import后`055-prompts-dialog-repaired-build.log`构建成功（1分56秒），lint恢复0错误/22警告。
`055-prompts-dialog-device.log`为**9项8通过、1失败，38.768秒**；
失败在蒙层用例读取面板偏移前bounds的前置检查，没有实际触发蒙层关闭，不能据此声称关闭行为失败或通过。
将显示标记移到实际内容后，所有显示断言核对真实可见内容；没有放宽JS返回值、网络计数或超时。
`055-prompts-scrim-build.log`主/测试APK构建成功（50秒），
`055-prompts-scrim-input.json`固定API35/x86_64的5592实例、本轮源码hash及二进制：
主APK `69b8d4dce2468898d1cbb6519645187d1ad6b49efb59bee88f5b3a82bc22b814`，
测试APK `25709c4bf640ad981a897a65ee4e7c4865c8726b37137bd74809d4a7ca01bf28`。
`055-prompts-scrim-device.log`**9/9通过，57.245秒**。
连续alert和文本取消后的策略、新文档恢复询问、真实蒙层触摸先hide再显示下一请求均通过。
这些都是loopback真实网页的设备专项，不覆盖全部B13或公网/物理机/正式发行。
最终`055-prompts-final-lint.log`成功（2分26秒），`055-final-lint.xml`为0错误/22警告，未屏蔽规则；
`055-jvm-summary.json`保存111项JVM报告的逐类计数与SHA，均0失败/错误/跳过。
`055-installed-version.txt`核对实际安装的versionCode=13、versionName=0.5.5。

## 私密首批：普通与私密合计11项通过

production源码已提交`2048cecea401a1d085692aa7489ead1e693a8968`。
随后仅新增私密测试，`055-private-prompts-build.log`测试包构建成功（33秒），
`055-prompts-ordinary-private-input.json`绑定相同主APK及新测试APK
`9edd151fc2bbbbef0db43175625ba3c913b7d6b3ae196af87ec407b240441c5d`。
同一受控只读API35实例5592起测没有既有私密tab，之前仅运行本批普通夹具；没有删除用户状态。
`055-prompts-ordinary-private-device.log`两个类合计**11/11通过，49.725秒**。
私密用例保持FLAG_SECURE、没有截图；DOMContentLoaded和双rAF后的ready beacon同步真实触摸。
实际text输入的合成值由JS标题与POST正文共同核对，关闭最后私密tab后prompt为空；
另在私密confirm待答时关闭全部私密tab，旧token不能影响新普通confirm，普通UI取消实际返回false。
这两项没有证明全部私密类型、native context销毁或磁盘/日志/Bundle零残留，相关门继续保留。
新增私密测试后的`055-private-prompts-lint.log`成功（49秒），`055-private-final-lint.xml`仍为0错误/22警告。

## 0.5.5 ARM64开发包：已验证打包

production源码`2048cec`的`055-release-build.log`成功（4分31秒），开启R8和资源收缩。
`055-release-input.json`记录AGP原包111,137,721字节，SHA-256
`0407a7db3c2ea3c91bdcb406a676fb88df4984dd853953120ab882d4f163efcd`。
`055-optimize-release.log`及`artifacts/releases/Miru-0.5.5-arm64.apk.verification-20261025-121338-8d7e8552/`
记录八步全部通过：1517项payload逐项保持、SDK对齐及v2/v3签名验证通过，证书与原包相同。
最终`artifacts/releases/Miru-0.5.5-arm64.apk` **107,987,120字节 < 110,000,000字节**，SHA-256
`0d2792006695fdbbffb38f1a319054c9fea9333ed0da501c0fa6abb470fb6c0b`。
仍为Android Debug证书`5aa1a00e59b8638298dbddcc5a61053ca245249136edb67199e0c9a3e4cb1f84`，不是正式签名。
`055-final-abi.json`独立核对17个arm64-v8a库均为ELF64/AArch64；
`055-final-omni-verification.json`核对4078项原payload、1项精确GEO修改、5项官方blocklist新增及原index保持，
omni SHA仍为`2a8da1a2c185d24f11e385e794b18b5d169a4e5afa64ce7f8882f8af33b14cb1`。
初次调用时PowerShell日期对象转字符串导致最低时间偏移8小时，初报告保存在`055-final-omni-initial-minimum.json`；
已按构建开始UTC `2026-10-25T12:08:14.9817471Z`重新执行全部检查，正式摘要中的最低时间已核对。
本批没有运行最终ARM64 APK；普通/私密11项运行的是固定x86_64 Debug包，不能扩大为ARM64运行或正式发行验收。

## 固定0.5.5 APK的系统与常规回归

只读软件渲染API35/x86_64新实例5594，启动记录`emulator-055-fresh-launch.json`，
`055-fresh-precondition.json`记录安装前无Miru包及固定APK hash；
`055-runtime-raw-permissions.txt`确认摄像头、麦克风、coarse/fine定位均未授予。
旧5592的关闭仅为换新系统权限夹具，没有作为下载/离线强停重启证据。
`055-runtime-device.log`**3/3通过，21.844秒**，实际系统拒绝、分别允许及近似定位均通过，
没有pm grant/预授权/合成系统callback。网站权限清理后，同一固定APK另起常规整套。

`055-full-input.json`记录production源码2048cec、测试源码81b2f8d和完整命令；
`055-full-device.log`终态**98/98通过，595.555秒**，`055-full-result.json`核对98个通过终态、0失败。
范围包括新增9项普通/2项私密prompt、站点权限及实际媒体撤销、普通导航、下载/离线、
三档benchmark和四条60帧GL链等已有常规用例。
排除DownloadRestartTest、OfflineRestartTest和独立运行的RuntimeSitePermissionIntegrationTest；
前两个重启门仍待，不把排除计为通过。

`055-full-runtime.log`只收集既有窄标签：混合下载点击撤销后pending在259ms清空，
benchmark FAST/BALANCED/QUALITY为212.6/286.6/494.6ms/frame（synchronized render）；
四链各60帧、1920×1080、13/21/23/2 passes，平均192.8/264.0/465.8/113.1ms。
没有并行Gradle/R8/录屏，没有放宽原期限、帧数或像素断言；
软件模拟器功能通过不证明16ms或物理GPU性能门，也不单凭后续通过认定0.5.3时序失败根因已修复。

私密其余矩阵、iframe来源、复杂提示更新、系统文件选择及完整站点矩阵继续在全量验收中保留。
98项常规回归与独立3项系统通过不等于完整B13、ARM64运行或正式发行。
