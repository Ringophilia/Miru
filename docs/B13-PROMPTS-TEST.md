# B13 页面 prompts：真实设备专项运行说明

这是0.5.5首批测试范围与运行说明。最初7项通过（33.102秒）；最新UI及真实内容定位对应整类9/9通过（57.245秒），固定输入、8/9中间失败与首轮超时历史见B13-PROMPTS-VALIDATION.md。独立专项通过不能代替全量回归。
`PagePromptIntegrationTest`使用应用真实BrowserStore/Gecko/Compose面板，测试进程只提供普通loopback网页。
不会构造PromptRequest、替换Web API、注入回调结果或关闭Gecko安全检查。

## 输入与判定

网页操作先等待真实Gecko蓝色按钮绘制，再注入可信触摸；原生回答通过稳定testTag点击。
读取`controller.prompts.prompt`仅用于等待类型及核对token/tab/origin/topOrigin，不把StateFlow出现当作网页回调成功。
网页标题来自实际JS返回值或HTTP响应；POST和认证另有服务端计数约束。
新建网页以及导航到idle/新confirm文档时，同时等待精确URL、网页标题和加载结束，避免旧文档同名标题提前满足等待。
固定AC/Gecko API只为Authentication提供请求源URI；其余本批类型断言`origin=null`，并单独核对当前顶层页`topOrigin`。
UI应显示来源不可用及当前页面，不能将顶层页当作已验证的请求frame来源；认证另断言真实401挑战的`origin`。
唯一直接调用回答API的地方，是主动发送旧token，证明它不会终结新文档/新标签的真实请求。

| 用例 | 真实触发与通过条件 |
| --- | --- |
| JS alert / confirm / prompt | 蓝色按钮调用原生`alert/confirm/prompt`；UI确认alert后JS继续、confirm确认/取消分别返回true/false、文本确认返回输入值而取消返回null。 |
| 连续提示抑制 | 实际勾选阻止后续提示，首个alert确认后后续原生alert自动结束、JS继续；新文档恢复询问。文本prompt勾选后取消也必须应用策略，JS收到null并继续。 |
| Scrim后下一提示 | 真实触摸Material3裸露蒙层，首个alert完成hide动画并取消；第二个原生alert必须以新token显示且可点击，网页脚本随后完成。 |
| Activity重建 | 文本prompt未回答时调用真实ActivityScenario.recreate；同tab/origin/topOrigin/token保留，服务端原文档只加载一次；随后UI取消得到JS null。重建本身不能取消网页请求。 |
| 导航作废 | 未回答confirm时真实导航；新文档再触发confirm，原token迟到回答不能改变新prompt，UI取消仍返回false。 |
| 关闭归属标签 | 关闭正在等待confirm的tab；另一tab触发真实confirm后，旧token回答无效且新UI取消返回false。 |
| beforeunload | 真实触摸取得用户激活后注册handler；导航时点击Stay保留旧地址/标题/原文档加载次数且目标请求数为0，再点击Leave实际加载目标一次。 |
| POST重新提交 | 真实HTML表单POST一次；公开reload必须发真实REPOST请求。取消不增加POST计数，明确确认只再提交一次。若固定Gecko公开reload没有触发该请求，该用例应失败并保留证据，不能改成手造回调或Assume跳过。 |
| HTTP Basic认证 | 普通loopback页面导航至真实401挑战；用UI输入合成用户名/密码后服务端认可并显示响应标题；新端口独立认证域中取消，401响应标题与零成功授权计数共同验证。不会输出Authorization值。 |

使用的UI tags为`browser_prompt_dialog`、`page_prompt_confirm`、`page_prompt_cancel`、
`page_prompt_text`、`page_prompt_username`、`page_prompt_password`、`page_prompt_stop_dialogs`。
本批只覆盖网页标准两按钮confirm；没有通过选择其他调用方动作来伪造中立按钮结果。

## 运行

复跑时先记录源码commit、应用/测试APK的SHA-256、设备serial/API/ABI和完整命令。
0.5.4固定APK的87项回归已独立完成；其结果不能套给本批。
设备测试期间保持二进制固定，不在渲染/benchmark验收期间并行执行Gradle或安装其他包。

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <isolated-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <isolated-serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <isolated-serial> shell am instrument -w -r -e class app.miru.browser.PagePromptIntegrationTest app.miru.browser.test/androidx.test.runner.AndroidJUnitRunner
```

最新整类9项已有明确通过终态；详情和二进制SHA以B13-PROMPTS-VALIDATION.md及原始日志为准。失败应保留原始instrumentation日志、
类型/token归属与计数判定；不得输出真实网站、表单内容、认证头、用户名/密码或私密源。
先独立运行该类，再决定是否进入常规回归；独立通过也不能覆盖整套回归失败。

## 私密新增两项：已取得专项终态

`PrivatePagePromptIntegrationTest`新增以下两项；与普通9项合跑为**11/11通过，49.725秒**，日志`055-prompts-ordinary-private-device.log`、输入`055-prompts-ordinary-private-input.json`。这不改变上文旧二进制9/9的历史范围。
使用真实Gecko私密页面、Compose提示面板与loopback服务端，不构造PromptRequest、不替换Web API或注入网页回答。

| 专项用例 | 真实触发与验收条件 |
| --- | --- |
| 私密文本回答与最后私密tab关闭 | 真实触摸网页按钮触发文本prompt，经UI输入本次合成nonce并确认；实际JS返回值同时由网页标题和服务端beacon核对。关闭夹具的最后私密tab后，确认私密tab已移除且prompt coordinator无pending。 |
| 关闭全部私密tab后的迟到回答 | 私密confirm未回答时调用真实`closeAllTabs(isPrivate=true)`；随后普通新tab通过真实网页按钮产生confirm。主动发送原私密token的迟到回答不得终结普通新请求；通过UI取消普通请求，JS与服务端结果仍为false。 |

两项保留`FLAG_SECURE`，不截图、不关闭或绕过安全标志。页面以`DOMContentLoaded`之后连续两帧
`requestAnimationFrame`再发布ready标题与服务端ready beacon；等待这些真实网页信号后，按EngineView屏幕边界注入可信触摸，
不依赖截图像素判断私密页面是否已绘制。

这组专项只在受控独立AVD、无用户私密测试态时运行；本轮5592最初从只读基础镜像启动、安装前无Miru，此前仅执行本批普通夹具。起测前确认没有既有私密tab；
若前提不成立，不得为满足前提删除用户私密态。保留起测前所有普通tab，仅清理夹具创建的标签。
`closeAllTabs(isPrivate=true)`用例须在此隔离前提下运行，不能混入用户会话。

nonce只用于进程内及loopback网页结果判定，不写Log，不通过对象`toString`或失败诊断输出其内容。
后续复跑继续另存命令、APK SHA和明确终态；两项专项通过不能代替磁盘、日志、
Bundle/保存状态的完整敏感值审计，更不能预记完整私密隔离验收通过。

## 尚未覆盖

- 私密其余提示类型、native context销毁和磁盘/日志/保存状态的完整敏感值审计；本批仅补私密文本与关页迟到回答，
  不为取得私密截图关闭FLAG_SECURE。
- 跨源iframe来源提示、更多队列/重复请求竞态、后台/前台、进程死亡、session替换及更多取消竞态。
- 中立按钮、认证错误重试/仅密码/代理认证、输入在重建后的完整保留策略与密码始终不进持久状态的取证。
- 文件/选择器、日期/颜色/上传、证书、通知、弹窗与其他B13类型；全部可访问性/语言/布局矩阵。
- 真实网站、ARM64物理机、release运行与完整B13验收。此文件不修改现有权限首批或0.5.4历史结论。
