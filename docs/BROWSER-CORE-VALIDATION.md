# 浏览器基础交互与清除数据验证

## Cookie 删除：固定 158 源码与已发现的两次失败

以下结论针对 Android Components `158.0.2`、实际选中的 GeckoView
`158.0.20261021001945`，不从桌面 Firefox 的相似 API 推断 Android 行为。

### 第一次失败：native port wrapper 身份不同导致等待 45 秒

`BrowserCoreIntegrationTest.cookiesClearByCreationTimeAndAllTimeIncludesPrivateJar`
首次运行中 Cookie 项等待 45 秒后返回失败、`completed` 为空。固定版本
`browser-engine-gecko` sources 中 `GeckoWebExtension.kt` 第 51–59 行显示：
background `onPortMessage`、`onDisconnect` 每次都构造新的 `GeckoPort(nativePort)`，
而 `onConnect` 保留的 wrapper 是另一个对象；`GeckoPort` 没有覆盖 `equals`。
因此用 Kotlin `!==` 比较回调 wrapper 与连接 wrapper 会拒绝真实响应，不能用于判断 native port。

修复后的判定使用独立的、未向网页暴露的 native messaging handler，以及仍在等待中的随机
UUID 请求 ID。上游 background 断连 delegate 已比较当前连接的 native port 后才转发断连，
应用层据此重建连接等待器并结束未完成请求。不能仅因为删除已发生就报告完成，必须收到对应请求的成功回复。

### 第二次失败：不带筛选的 all-time 调用保留了 private jar

移除 wrapper 身份误判后，真实 Gecko 文档测试已证明时间分界前的普通 Cookie 保留、
分界后的普通 Cookie 删除。随后 all-time 调用删除普通 Cookie，但测试第 66 行仍读到
private Cookie。这是第二个独立故障，不能把普通 jar 的成功算作整个 Cookie 项成功。

从实际 Gecko AAR 内原始 `assets/omni.ja` 只读提取的
`chrome/toolkit/content/extensions/parent/ext-browsingData.js`（SHA-256
`fe1a1ba619e43ff4a622ddaf39783a224c8a7297c4453f78384c218507e22075`）给出具体分支：

- 第 86 行：`since`、`hostnames` 或 `cookieStoreId` 任一为真才进入逐 Cookie 枚举。
- 第 94 行：无 `cookieStoreId` 或 private store 时，显式加入
  `getCookiesWithOriginAttributes({ privateBrowsingId: 1 })` 的 Cookie。
- 第 103 行：按真实 `creationTime >= since * 1000` 删除；不是最近访问时间。
- 第 107 行：显式 store 筛选通过 Cookie 的 origin attributes 判断 store ID。
- 第 125 行：完全不带筛选的调用走 `cookieMgr.removeAll()`；实际设备测试证明该路径留下 private jar。

Android 的 `modules/ExtensionBrowsingData.sys.mjs`（SHA-256
`f56a86807a03eca12510e0dd14bd8fb60ca7112f594763bdc130f7d93cb4195b`）第 46–55 行仅将
downloads、formData、history、passwords 转发给应用，Cookie 使用上述真正的 Gecko 实现。
`ext-toolkit.js` 的 `getCookieStoreIdForOriginAttributes` 优先将 `privateBrowsingId` 映射为
`firefox-private`。

当前修复保留原始 `removeCookies(since ? { since } : {})`，随后再调用
`removeCookies({ ...sameOptions, cookieStoreId: "firefox-private" })`。
后一次调用强制进入 private jar 的显式枚举分支；有限时间范围的两次调用使用同一截止时间。
只有两个 Promise 都成功后才向 native 回复成功；不增加 `cookies`、页面访问或内容脚本权限，
不通过 IPC 传输 Cookie、网址或异常原文。扩展版本升至 `1.0.1`，以便内置扩展重新载入时识别资源变化。

此处记录的是源码依据和修复，不能代替修复后的设备验收。需由 root 补充新构建、日志、
private jar 分界前保留 / 分界后删除、all-time 两个 jar 都为空的真实运行结果。
还应保留 Cookie 项失败时其他所选项目结果能分别报告的验证，避免用部分成功覆盖失败。

原始 Gecko AAR 的 SHA-256 为
`a3af9d5ffb1f898df5b8782d083b69c4274c4f3275e31f3dac33d0d2aa16c692`；对应源码 revision 为
`e4ce606692cd81e6898547e2edd7c552fefcbb04`。可对照
[固定版本的 ext-browsingData.js](https://hg.mozilla.org/releases/mozilla-release/file/e4ce606692cd81e6898547e2edd7c552fefcbb04/toolkit/components/extensions/parent/ext-browsingData.js)。

## 实现与当前直接证据（0.5.1 / code 9）

- 网页上下文菜单来自实际 EngineSession `onLongPress`。菜单随标签、导航、重载和进程变化失效；链接、图片、视频动作由当前文档身份校验后转交应用。新标签保留原私密属性，单独提供无痕打开。图片复制 / 分享使用校验过的 PNG 等栅格或 SVG 原始字节，存于有容量和有效期限制的内存 provider；URI 为随机 token，只读授权，不含源地址或认证。HTTP 获取不使用磁盘缓存，每跳剥离跨源认证并限制 Referer；10 项固定源策略单测通过。
- 顶 / 底工具栏按 Gecko compositor 的真实滚动位置移动，保持同一 EngineView。下拉刷新由 AC SwipeRefreshFeature / Gecko APZ 允许接管的结果驱动，并显示 Material 3 指示器。加载、编辑、覆盖界面、页顶和引擎请求强制展开工具栏。
- 清除界面包含历史、Cookie、站点存储、网页缓存、权限设置、HTTP 认证、页面表单 / 导航、播放进度和播放缓存，执行前明确确认，报告每项结果。历史 / Cookie / 进度支持时间范围；Gecko 158 其余清理 API 无真实时间范围，有限范围时禁用这些项目并说明。不会把全量删除冒充时间筛选。
- 表单清理重建会话而保留标签 ID / 地址，丢弃当前表单和前进 / 后退状态；关闭旧会话并清除撤销、缩略图和会话备份。自动及手动保存使用同一锁，锁内读取最新状态，避免清理前的排队快照回写。未选择表单的其他项目不隐式关闭或重建标签。

`final-build.log` 构建 Debug / AndroidTest 并执行全部相关 JVM 测试成功，汇总 XML 为 **84/84，0 failure / error**。最后更新测试的构建为 `presented-frame-build.log`，成功27秒。

`artifacts/browser-core/presented-frame-device.log` 为 API35 x86_64 上 **12/12 通过，34.345秒**，直接覆盖：

1. 普通 / 无痕各两条真实 Cookie，边界前保留、边界后删除，全部时间两个 jar 清空。
2. 实际设置页面选择最近一小时、禁用不支持项、取消 / 确认，最近历史和进度被删、较旧记录保留。
3. 实际 localStorage 清空且 Cookie 保留；填充实际显示的表单后清理，原标签地址保留，表单为空、返回栈丢弃。
4. 实际长按复制链接文字、图片内容 URI 原字节、新无痕标签；私密图片复制带敏感剪贴板标记，且没有图片磁盘目录。
5. 顶部及底部工具栏跟随真实触摸滚动隐藏 / 展开，页内滚动不触发刷新，回到页顶后下拉增加真实来源加载次数。
6. 6 项 provider 测试：PNG / SVG / UTF16 SVG 原字节、只读 opaque URI、错误类型 / namespace / 畸形 XML、DTD / 外部实体拒绝。只解析 SVG 结构，不执行脚本或读取外部资源。

## 失败记录与修正边界

前序日志保留，不用最后通过覆盖历史失败：

- `initial-device.log`：7项5通过2失败（103.768秒），Cookie响应超时、下拉刷新未发生；两个播放器回归通过。
- `port-fix-device.log`：Cookie时间范围已生效，但全部时间遗留无痕 Cookie；刷新仍失败。
- `cookie-private-device.log`：更新扩展时再次出现响应超时。Gecko私密权限更新返回新的扩展实例，改为在最终实例上注册消息处理器，避免升级 / 重载时丢失连接；`final-extension-device.log` 随后真实 Cookie 专项1/1通过（9.049秒）。
- `refresh-touch.log` / `refresh-detail.log`：APZ仍报告可向上滚动，拒绝父层接管。错误使用正的可见高度作裁剪；按 [Firefox 158 官方 EngineViewClippingBehavior](https://raw.githubusercontent.com/mozilla-firefox/firefox/refs/tags/FIREFOX_158_0_RELEASE/mobile/android/android-components/components/ui/widgets/src/main/java/mozilla/components/ui/widgets/behavior/EngineViewClippingBehavior.kt) 改为显示0、隐藏负偏移，并精确吸附到负最大高度。没有绕过网站自己的手势或强制拦截。
- `clipping-fix-device.log`：9项8通过1失败（130.295秒）；刷新和2项实际benchmark UI通过，表单点击在首帧前发出。`final-targeted-device.log` 12项10通过2失败（45.308秒），另有底栏测试使用裁剪后的边界无法观测屏外位置。
- 表单测试现先等待截图中真实按钮的指定颜色像素，再发实际触摸；`core-form-presented.png` 经目视核对确实显示按钮和表单。底栏用实际未裁剪位置验证移动，保留原滚动与请求数断言。`geometry-device.log` 两项通过（13.913秒），其后上述12项全部通过。

## 仍需完成

全套常规回归、ARM64 release / lint 和最终包摘要由后续终态补记；这些结果未在本节预先计为通过。

首次全套 `final-full-device.log`：**68 项67通过、1失败，461.215秒**，只排除需外部强停的 DownloadRestartTest / OfflineRestartTest，已包含uBO首装专项。唯一失败在网页视频退出全屏：测试关闭长按倍速后，新长按应正常出现视频上下文菜单；它没有先区分并关闭此菜单。`context-fullscreen-device.log` 补上菜单动作断言与关闭后，又在窗口尚未归还焦点时点击全屏按钮失败。最终测试等待真实菜单消失、Activity取得窗口焦点，再保留全屏按钮触摸与单次返回退出的完整断言；`window-focus-device.log` 两项网页媒体专项通过（22.645秒）。生产返回逻辑未改，菜单关闭与全屏退出分别验证。最终整套重跑仍以新终态日志为准。

- 真实站点 / 物理设备、页面已有手势、嵌套滚动、网页固定底部元素、大字体 / 旋转 / 键盘完整矩阵。
- blob 图片的原文档读取和新标签 / 下载 / 复制 / 分享路径；HTTP / data 图片部分已有实现，不能把 blob 仅显示菜单视为完整支持。含 DTD 的 SVG 明确不解析；实际跨应用接收图片、视频播放 / 下载菜单全部入口仍需专项。
- 表单清理后的进程终止 / 重启与会话恢复；权限、认证缓存、网页网络缓存的每类真实删除夹具。Gecko回调成功不代替各存储的直接取证。
- 真机通知 / 下载和离线文件确实保留、所有站点设置、退出时清理、首次引导、在线建议等其他 P0 / P1 条目继续按全量矩阵推进。

## 2026-10-24 新来源策略与私密表单修复

中断的 `delivery-full-device.log` 只有部分进度，不作为成功记录。新启动的同一自有 API35 模拟器上，
`delivery-resumed-device.log` **68项67通过、1失败，421.720秒**：PixelCopy 成功但拷到直接渲染 Surface
的初始黑帧。测试保留原有超过100个非黑像素要求，改为在8秒内等待实际呈现，独立于播放时钟；
`fallback-frame-device.log` 该项 **1/1，通过，4.892秒**。不修改生产回退行为。

新来源策略首轮 `image-policy-device.log` **19项15通过、4失败，149.113秒**：
私密页面截图因 FLAG_SECURE 无法作为图片绘制证明；同URL重载/SPA relay与HTTP策略合并存在竞态；
仅清表单会关闭最后一个原生私密 context 并真实丢掉 Cookie。
私密夹具现保留并断言 FLAG_SECURE，以实际 image.onload 和双RAF标题确认就绪；来源策略按实际导航
合并HTTP基线，SPA使用当前文档地址，旧relay有界退休；表单修复和证据见 [PRIVATE-FORM-REVIEW.md](PRIVATE-FORM-REVIEW.md)。

`core-final-v2-build.log` Debug/AndroidTest/Android JVM 构建成功（77秒）；
`core-final-v2-device.log` **20/20，通过，136.888秒**：6项实际来源策略、2项私密表单、
6项原浏览器核心和6项内存图片provider。策略夹具逐次观察服务端 Referer，并核对复制的PNG原始字节，
不是只检验生成的请求对象。

额外静态复核发现：未注入helper的srcdoc/特殊frame若紧接另一frame按相同图片URL，旧实现的
“同URL且菜单时间差小于2秒”可能接受前一次触摸。真实生产background的新增协议断言先失败
（`press-identity-protocol-before.log`），现把原生 ACTION_DOWN 开始时间绑定到菜单，
helper保留原pointerdown时间，background拒绝开始于本次原生触摸之前的记录。
仅为时钟精度容忍2ms，不再靠两个contextmenu事件相近判断同一目标。桥接版本为0.3.5。
工具 `node tools/context-image-policy-test.cjs` **14/14通过**，加载真实background/classifier，
不复制策略实现；这些协议检查不替代Gecko真实事件与HTTP证据。

新增srcdoc设备夹具第一次在“两次初始HTTP图片请求”前置条件超时（`press-identity-repro-device.log`，23.029秒）：
Gecko可合并相同图片URL请求。现改为确认第二个真实绘制图片后发触摸，保留“不允许新请求带旧文档Referer”断言。
特殊frame没有自己的可信helper时可以明确不可用，完整srcdoc/blob动作仍未计完成。
最新手势绑定的设备结果与最终全套/发布构建将在终态后补记。

`gesture-binding-build.log`：所有 Android JVM、`:core:common:test`、Debug/AndroidTest 构建成功（62秒），
汇总为 **93/93，0 failure/error**（`core-051-jvm-summary.json`，含core/common的10项）。
`gesture-binding-device.log` **13/13，通过，76.145秒**，含7项来源策略/手势和6项浏览器核心；
srcdoc连续长按现在不会把旧frame的Referer用于新请求，未修改普通/私密PNG字节要求。

最新全新资料常规回归 `core-051-full-device.log`：**77项75通过、2失败，639.112秒**，包含uBO首装，
只排除两个外部强停类。两个失败为：

1. 实际benchmark UI的Quality档45秒内没有足够有效渲染帧，Fast=254.9、Balanced=352.4ms/frame，
   计时类型均为synchronized render；Quality无结果，测试按原要求失败，未把失败当作“无推荐”成功。
2. 三档真实Anime/FSR的60帧测试，在原60秒等待内未完成；保留全部帧数、像素和计时要求。

其余75项通过，包含本批所有核心/来源策略/私密表单/provider、网页全屏、实际故障回退、
超分取消/后台释放、下载/离线/通知/字幕与音轨。不能因此把整套回归、超分性能或M3标记完成。
设备thermalservice报告状态0，运行期间没有并行R8或录屏；尚未证明两个超时的根因。
固定标签日志为 `core-051-full-render.log`。测试退出后查询进程内存时进程已结束，
`core-051-full-memory.log` 只记录“No process found”，没有可用内存样本，不能据此排除内存压力。
后续须隔离复跑并保留本次失败，不能增加期限、减少有效帧或换用虚构计时来报绿。

被测Debug APK：474,012,105字节，SHA-256
`9bb49b90e9f415a200c91b0d216b22b08c3314fe193e4ef788fc7b8348e9debb`。
测试包：8,012,259字节，SHA-256
`2ee38dd409cc207712e123de962eaa4188d491874b72335ac59612a17d6d211f`。
Debug包含模拟器ABI，不能用于单ARM64体积门。

## 最新源码的发布打包检查

`core-051-release-lint.log`：ARM64 release与Debug lint构建成功（5分28秒）；
最终lint XML为 **0 error、22 warning**，未改门槛或隐藏问题。
原AGP release：111,075,393字节，SHA-256
`7db5436c71bb90a4ee943ef15dc5fece08101118d71317626067561b1210a39a`。

经已提交工具链复用未改变载荷的压缩字节、普通对齐和重新签名，最终文件：
`artifacts/releases/Miru-0.5.1-arm64.apk`，**107,892,912字节**，SHA-256
`2364c2ad74bc46bbd9d42738e2f631daca588f47e4147fa4103c13ceaaf91362`。
**1517**个payload的路径/长度/CRC/SHA/压缩方式与最新原包逐项一致；
SDK对齐/签名与原证书集合相同，只有arm64-v8a的17个native库，严格小于110,000,000字节。
证据目录为同名 `.verification-20261024-084733-8563a5f9/`。
`core-051-final-omni-verification.json` 另证明原4079个Gecko资源和5个官方blocklist新增全部保持，
未删语言/安全/XPI/许可来压体积。

签名仍为本机Android Debug，证书SHA-256
`5aa1a00e59b8638298dbddcc5a61053ca245249136edb67199e0c9a3e4cb1f84`。
此处证明最新源的打包/体积门，不证明ARM64真机运行、正式分发密钥或整个release/M4门完成。
测试专用超时诊断随后仅修改androidTest源码，不影响上述发布payload。

## 软件渲染的隔离复跑

只强停测试应用再启动新进程，保留同一模拟器/应用资料；不同时运行Gradle/R8/录屏。
`render-diagnostic-build.log` 测试包构建成功（75秒）；仅增加真实超时状态诊断，未改60帧或等待期限。
`isolated-render-device.log` 两个失败用例 **2/2，通过，195.583秒**。
`isolated-render-measurements.log` 记录benchmark实际三档289.9/315.1/533.5ms/frame，
都是synchronized-render，均不满足16ms推荐；四条实际GL链均完成60帧，输出1920×1080、13/21/23/2 passes，
继续保留像素断言。不能把此隔离通过写成前述77项全套已通过，亦未证明长期运行超时根因。

随后直接查询SurfaceFlinger与AVD配置确认原5570为Google SwiftShader软件GLES3.0，
AVD `hw.gpu.enabled=no`；Windows同时存在RTX3080Ti和Intel UHD显卡。
现已结束自有只读5570实例，临时通过SDK支持的 `-gpu host` 在5572启动同一只读AVD，
不修改保存配置；待实际GL驱动确认和该实例的完整回归终态后再补证据。
