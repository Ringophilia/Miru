# B13 目录上传首批验证（0.5.9，完整矩阵未验收）

本批在 `codex/folder-upload` 独立工作树从 `924c97a` 开始实现，首构建终态后fast-forward至主分支 `517f708`，保留目录改动并接入capture18项最新夹具；设备测试后又fast-forward至仅补0.5.8证据的 `596c8eb`，未改变固定主APK。首批0.5.9/code17源码已提交`348b9bc`并快进至主分支。
**真实TREE首批专项已通过；完整目录上传、B13、生命周期与最终发行仍未验收。** 当前API35/x86_64证据不能代替其他provider/系统版本、ARM64真机或正式签名发行。

初次排错构建 `artifacts/folder-upload/build-1.log` 以旧code16标签执行，**BUILD SUCCESSFUL in 4m**：engine JVM **90/90**，包含树复制 **10/10**，0失败/错误/跳过；主/测试APK与Debug lint通过，lint **0 error / 22 warnings**。原XML汇总和仅供编译排错的code16 APK快照在同目录，不能冒充固定发布的0.5.8。

## 固定构建、APK与真实设备结果

`build-2.log` 在fast-forward后以0.5.9/code17冻结候选执行engine JVM、主/测试APK、lint，**BUILD SUCCESSFUL in 2m32s**；engine90项沿用未改变源码的已通过XML，lint0错误/22警告。`059-candidate-20261028-1023/` 固定主/测试APK、source-sha256、badging、JVM与lint汇总。后续仅改测试驱动，`build-3-driver.log` 的assembleDebugAndroidTest/lint **1m6s成功**，lint仍0/22，生产主APK逐次SHA保持一致。

跨模块首轮暴露旧顶栏测试的SDK页顶门后，仅修正该测试的真实位置/新文档判据；`059-top-positioned-build.log` 的测试APK/lint **1m57s成功**，lint仍0/22。此批JVM证据仅为engine **90/90**（含树复制 **10/10**），不代表全工程JVM总数或新增全量运行。

| 固定文件 | 字节 / SHA-256 | 使用范围 |
| --- | --- | --- |
| `059-candidate-20261028-1023/Miru-0.5.9-debug.apk` | 474,055,267；`6616ef8afcc4cf29b97e457943f59f9e3704efcd369a4cca76be433cbe51130b` | 本批全部真实设备测试的同一个主包；aapt2确认code17/version0.5.9 |
| 原测试 `059-candidate-20261028-1023/Miru-0.5.9-androidTest.apk` | 8,530,623；`0d936cc4725b91c29fb26776dac30a0ea782fa0be0547948c327753559878d39` | 仅首轮失败；不与修正后包混用 |
| 修正后测试 `059-tree-driver-binaries/Miru-0.5.9-tree-driver-androidTest.apk` | 8,533,666；`d15f6f340aa0580b2e6706be124716de2cbf6adc32ba9af799e63f812422dcb7` | 单方法复测及整类5项；snapshot.json保留测试源码SHA及主包不变校验 |
| 最终测试 `059-top-positioned-binaries/androidTest.apk` | 8,536,836；`3585f319bc85b1c94058207798515a21b4a003cc759ce9f5353dbde796d16230` | 显式TOP专项Core7与重新整批运行的49项；snapshot.json及static-scope记录测试变化，主包仍为6616… |

所有相对证据路径均位于本工作树 `artifacts/folder-upload/`。设备为受控 **emulator-5600、API35、x86_64**；输入JSON保留确切APK SHA、版本、源码基线、命令及范围。

| 执行 | 真实终态 / 范围 |
| --- | --- |
| `059-folder-first-device.log` / `059-folder-first-input.json` / `059-folder-first-result.json` | **失败，56.136秒**；1个方法、JUnit报告2条失败（主体chooseTree超时及@After超时）。当时尚未完成系统授权，生产复制/原生二次确认链没有因此被验证。 |
| `059-folder-driver-fixed-device.log` / 同名input/result JSON | 原完整目录方法 **1/1通过，21.03秒**；真实系统TREE及native确认、完整路径/bytes、grant基线与lease结束。 |
| `059-folder-complete-device.log` / 同名input/result JSON | 同一主/测试APK整类 **5/5通过，50.447秒**；完整目录、私密owner关闭、空目录真实change、真正native二次DENY、显式MIME不保真的诚实整体失败。 |
| `059-native-prompt-regression-device.log` / 同名input/result JSON | 跨模块首轮 **48/49，338.146秒**；Folder5、File18、Page9、Private2、站点6、权限生命周期2及Core中6项通过。唯一失败是原顶栏测试L253的SDK canScrollUp须在8秒内变false的旧页顶门，失败日志原样保留。 |
| `059-top-positioned-core-device.log` / 同名input/result/runtime | 最终测试包的Core整类 **7/7通过，150.868秒**；显式TOP、真实新doc页顶与实际刷新，生产主包未改。 |
| `059-native-prompt-regression-fixed-device.log` / 同名input/result/runtime | 同一最终主/测试APK重新单次执行上述7类，**49/49通过，602.313秒**；不是用隔离结果拼接首轮48/49，不是完整应用常规回归。 |

首轮实际UI根因：DocumentsUI的“USE THIS FOLDER”和随后系统授权“ALLOW”共用 `android:id/button1`。旧驱动把首次按钮误判为ALLOW，未再点击真正系统授权；清理又仅Back一次，退掉授权dialog后仍停在DocumentsUI，造成第二个超时。保留 `059-folder-first-ui.xml` 与 `059-folder-system-confirm-ui.xml`：前者为目录界面USE THIS FOLDER，后者有真实alertTitle和ALLOW/CANCEL按钮。未以伪造result、加时限或改生产绕过失败。

驱动修正按已观察的breadcrumb/按钮文本识别Use，再观察真正系统alertTitle并点ALLOW，分别要求实际转换；@After先退系统dialog，再退DocumentsUI，并保留主体异常、将清理异常作为suppressed。首轮日志/XML原样保留。

顶栏测试现显式设置TOP、断言实际位置并在finally恢复偏好；原手势与8/15秒期限不变。唯一新doc的owner/URL/title/!loading/精确GET及双rAF DOM/visual页顶取代独立SDK缓存门，真实下拉后还要求该doc新GET和新beacon。最终TOP运行的SDK canUp实际为false；独立深滚行为项另记录新doc DOM/visual/flow为0、SDK仍true且真实刷新成功，不能混为TOP复现了相同数值。详见[BROWSER-CORE-VALIDATION.md](BROWSER-CORE-VALIDATION.md)。

## 生产候选范围

- 真实 Gecko FOLDER 请求经用户的 ACTION_OPEN_DOCUMENT_TREE，专用receiver仅在进程内保留结果。
- query / open / 完整树复制与路径校验均在IO；查询/打开有CancellationSignal，复制取消会关闭当前流。
- 完整镜像真实根名及层级，支持超过旧Java深度5的目录、不同子目录同basename、Unicode、空目录和零字节文件；不传tree URI给同步Java遍历。
- 公开 native FilePrompt.confirm 仅提交单个已验证本地根目录；真正FolderUploadPrompt由Gecko产生，用户明确ALLOW后才由Gecko扫描并交付网页。
- 副本在首次回调前移入独立等待确认链，receiver结束只释放本次临时结果授权；拒绝清本次副本，允许后保留到文档/session/context结束。
- 不调用takePersistableUriPermission，不全局撤销用户此前可能为下载目录授予的相同TREE权限。平台实际临时READ/WRITE应按基线对照验证，不能把仅请求READ写成系统必然只授READ。

## 不能伪装完整支持的边界

公开本地root路线不能保留同级重复DISPLAY_NAME，也不能可靠保留与basename推断冲突的显式provider MIME。首批对此明确整体失败，不覆盖/改名/漏项后成功。virtual documents、循环、非法路径、不可读条目、超文件/节点/实际bytes预算也整体失败。后续metadata保真仍需评估完整prepared manifest路线，不能据普通目录通过关闭整个目录矩阵。

当前预算：512文件、4096总节点、实际可用空间保留64MiB；预算不是静默截断深度。所有叶文件复制完成、队列穷尽后才提交原生根目录。

## 原生两阶段归属与异常回收

第二FolderUploadPrompt没有公开父请求ID。Native adapter在真正提交第一次目录root前占用每session单一confirmation槽，保存原始文档身份及record，第二原生prompt消费该record。BrowserPrompts还须以第一Folder record引用身份匹配授权链，并延续第一owner的hardGeneration，目录名只是附加核对。

取消/切换/超时不会猜测清掉原生槽。旧原生确认未实际排空时，同session的新目录root不能提交，防止同DOM同名迟到确认被新流程认领；旧消息到达后真实DENY。槽不保留lease、路径、原始URI或强request/callback引用。

等待“第二原生prompt出现”最长30秒，超时回收副本和可见BUSY并报错，**不清原生tombstone、不自动ALLOW**。收到真正prompt后立即停止这段计时，用户确认本身不设自动期限。若原生终态永久缺失，该session的目录交付继续失败关闭；界面明确提示关闭此标签并在新标签重试，不能引导无限同标签重试。

仍需真实证明该串行槽在native回入、迟到、取消、关闭session和进程死亡中的行为。源码推理不能代替这些设备证据。

## 测试候选

`UploadTreeStagingStoreTest` 有10项真实临时文件/流单测：深度9与同名不同路径、空/零字节、累计实际bytes、同级冲突整次清理、循环与正常共享文件ID、文件/节点预算、非法路径、读取失败、阻塞读取取消、孤儿清扫与活租约。首构建10/10通过；不证明真实SAF/provider或Gecko目录链。

`FolderUploadIntegrationTest` 的5项已按上述固定包在API35通过：完整目录、私密关闭owner、空目录真实change、真实二次拒绝、扩展名缺失而provider显式PNG的诚实失败。使用独立测试APK静态Java DocumentsProvider、真实DocumentsUI TREE、真实Gecko prompts和网页POST原bytes；不构造PromptRequest/ActivityResult/FileList。

完整树包含8层目录、两个不同路径same.txt、Unicode PNG、zero.txt和空子目录。通过断言覆盖全FileList name/type/size/webkitRelativePath及每份POST原bytes、真实二次确认、系统选择后未自动上传、copy后临时授权回到原基线、没有新增persisted grant、文档结束后租约消失。空目录经真正确认ALLOW后收到真实空FileList/change，拒绝为实际DENY后Inspect空选择及零bytes；不能把这些初始空选择结果扩大为“拒绝保留已有选择”已通过。

设备驱动当前仅验证已观察的DocumentsUI版本/语言和受控provider；未知界面仍须实际核对。剩余系统TREE取消保留已有FileList/lease、两阶段重建、IO中owner作废、真实迟到系统结果、实际触发的旧native确认撞新同名请求、预算/故障provider、进程死亡及私密持久化取证继续待补。metadata特殊情况、完整目录生命周期、完整B13均未闭合；0.5.9完整常规回归、R8/ARM64运行及正式签名仍待，后续R8构建及108,024,040字节ARM64开发包见[B13录音记录](B13-AUDIO-CAPTURE-VALIDATION.md)。
