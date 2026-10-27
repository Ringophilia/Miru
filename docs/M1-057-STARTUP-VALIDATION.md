# 0.5.7 启动就绪与回归取证

当前为开发批次，不关闭完整M1、B13、M3或发行门。0.5.6源码`56a22a1`的固定APK完整回归为
102/106（1328.953秒），剩余uBO READY、两项定位重新询问和QUALITY benchmark失败；
原始输出与失败摘要保留在`artifacts/browser-core/056-committed-full-*`，不能用隔离复跑抵消。

## 已证实的就绪事件丢失

`057-benchmark-diagnostic-runtime.log`来自修改启动顺序前的0.5.7诊断APK，同批二进制摘要见
`057-geolocation-diagnostic-input.json`，不是后续修复包：

| 2026-10-26日志时刻 | 真实事件 |
| --- | --- |
| 14:39:03.715 | Runtime创建完成，开始旧定位授权迁移。 |
| 14:39:04.365 | SDK明确记录`No listener for GeckoView:WebExtension:OnReady`。 |
| 14:39:04.600 | 定位迁移完成。 |
| 14:39:04.737–04.742 | 才注册正常扩展delegate。 |
| 14:39:04.813 | 安装配置结束，`setup=true`、`readyVersion=false`，状态仍为INSTALLING。 |

这直接证明了应用等待迁移期间的一次真实丢事件；没有把“已安装/能拦截”推断成READY。
固定158源码也明确：WebExtensionController设置delegate时才注册OnReady监听，列表接口不补发该事件。
缓存源码位于`C:/Users/tocml/AppData/Local/Temp/miru-ac-158/`；SDK来源和版本继续按`UPSTREAM.md`固定。

## 修复边界

- Runtime创建后立即安装只收存生命周期事件的早期delegate；仅保留真实uBO `onReady`对象，未安装、未开页面、未设置tab/action handler或审批权限。
- 安装、启用、禁用、卸载与失败事件会使过时证据作废；扩展进程被禁止启动也使缓存作废。
- 旧GEO授权仍须先移除并读回验证。之后才构造/启动正常engine、接管delegate、安装SDK列表返回的全局handlers、恢复/连接页面。
- 早期事件只提供真实就绪版本证据，不能以旧wrapper覆盖后续列表的新enabled/private状态，也不由列表制造就绪。
- 进程被禁止启动后保持FAILED；metadata重试不解除阻断，迟到ready不能擅自恢复。用户明确Retry才调用公开的`enableExtensionProcessSpawning`，随后仍须真实READY。
- 使用公开的原生delegate/setter及公开`GeckoWebExtension(native, runtime)`构造器，无SDK反射或新的资源修改。

新状态测试覆盖交接一次、目标addon隔离、失效/迟到事件、明确重试和旧事件不覆盖当前metadata。
`057-retained-ready-build.log`主/测试/R8 release与lint构建成功（3分3秒），JVM **141/141**，0失败/错误/跳过。
构建前的`057-early-ready-build.log`保留测试误用不存在的公开tabId字段的编译失败；
修复改为真实请求的origin/privacy及当前选中fixture匹配，没有为测试向生产添加该字段。

## 定位测试的真实文档前置

两项失败原来用相同标题/蓝色按钮等待替换文档，不能排除点击旧页面；reset还会自动重载。
fixture现为每个真实GET分配整数docID，真实双rAF和请求/结果经HTTP确认。
page/reload/navigate及触摸前同时检查实际目标URL、对应docID标题、最新served ID、同ID rAF、加载完成与选中owner；
新文档必须严格超过动作前docID。reset自动重载完成后再执行原来的显式reload，所有原动作及旧token负向断言保留。
私密自动请求继续保留FLAG_SECURE，使用新GET/真实request beacon和原生prompt的origin/privacy，不截图、不拿loading结束代替pending请求。
原30/20秒门、生产授权策略、GEO overlay和原Android预授权范围均未放宽。

较早仅加诊断、尚未收紧前置的`057-geolocation-diagnostic-device.log`两项2/2（27.242秒），
不据此声称原完整回归的两项失败已经唯一归因或修复。

## benchmark诊断与已有结果

`MiruBenchmarkRun`只记录固定事件、枚举、数值和布尔，包括阶段、有效帧、generation、播放器状态、期限和释放交接。
未改45秒/60帧、720P输入、1080P输出或三档画质。
`057-benchmark-diagnostic-device.log`实际为**2/2通过（90.394秒）**；对应input误将期望数量记为1，
原input保留，实际范围以两项完整status和结果为准。
首项三档各取得60个有效帧，均为SwiftShader的synchronized render time：

| 档位 | 平均ms/帧 | 本档耗时ms |
| --- | ---: | ---: |
| FAST | 207.2652 | 16584 |
| BALANCED | 274.4351 | 19507 |
| QUALITY | 473.0538 | 34190 |

此轮释放交接均小于600ms；第二项实际验证取消/重开/后台释放。
正常完成的孤立轮不能解释旧完整回归的QUALITY超时，也不是物理GPU的12ms性能成绩。

## 当前修复包与待验收

固定二进制保存在`057-retained-ready-binaries/`，完整摘要见`057-fresh-adblock-input.json`。
全新只读API35/x86_64、安装前无Miru包的首装专项 **1/1（24.268秒）**，真实安装、拦截、开关及popup通过。
同profile的两个独立instrumentation进程首启专项分别**1/1（13.59秒）**、**1/1（13.756秒）**，
见`057-restored-adblock-1-*`及`057-restored-adblock-2-*`；两轮实际ready均由正式delegate处理，`early-ready-handoff present=false`。
不能把它们写成缓存非空交接已经实机覆盖，真实旧GEO存储迁移fixture仍只有未编译草稿。
四项Android权限均未授予前置下的系统UI专项**3/3（15.432秒）**，见`057-runtime-fresh-*`；
收紧真实docID/rAF门后的站点权限整类**6/6（25.398秒）**，见`057-site-document-gates-*`。
前者未预授权，后者保留原site-policy套件显式预授权，证据范围分开。

最终ARM64开发包`artifacts/releases/Miru-0.5.7-arm64.apk`为**108,011,752字节**，SHA-256
`74e95bc52ef4bf7186f2a852ca1017392a5c36aff21060ddec714d74816d867e`。
同名`.verification-20261027-061211-10e647bb/pipeline.json`为PASSED，1518项payload、对齐及原Android Debug证书一致；
`057-release-native-verification.json`验证17个ELF64/AArch64库；`057-release-omni-verification.json`验证4077原项不变、
GEO/导航两项精确修改和5项官方新增，omni仍为`f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`。
R8 release构建及最终lint 0错误/22警告通过；没有ARM64/R8运行或正式签名声明。

## 已提交版本完整常规回归

提交`041ec81`的固定包、API35/x86_64/SwiftShader，`057-full-device.log`终态为
**103/106通过、3失败，883.116秒**；输入、逐项结果及运行阶段见`057-full-input.json`、`057-full-result.json`、`057-full-runtime.log`。
uBO READY、此前两项定位重新询问、全部页面提示和上传均通过；没有把旧失败删除或将隔离通过拼接成全绿。

- 底栏L213隐藏与L221恢复已通过，失败是新导航后L224的`canScrollVerticallyUp`页顶判断。原GET/loading门未核对新URL/owner，SDK DOM滚动缓存与Compositor流也需对照，尚未确认根因。
- 长按用例普通图片URI、类型与原字节校验已通过，失败是L170再次打开链接菜单，不是图片策略未返回；当轮policy-live/query-live日志保留。
- QUALITY在45,084ms期限时仍正常播放/渲染，仅取得45个有效帧，平均781.725ms/帧、23个passes、无renderer error。明确为COLLECTING阶段收帧不足，本轮不是释放交接卡住。原60帧/45秒门未改，软件GPU失败不作为物理GPU成绩。

非空早期事件交接、上述三项完整范围及release运行仍需证据。
真实扩展进程崩溃/恢复、多轮真机、更新及d3ward矩阵仍未由状态单测或一次首装证明。
