# B13 网页文件上传后续接入计划

状态：**未实现、未构建、未执行设备验收**。这是基于固定源码的后续设计与验收门，
不是文件上传支持声明，也不改变现有页面 prompts、权限或历史版本的验证结论。
当前 `BrowserPrompts.supported()` 尚未接入 `File`、`Folder`、`FolderUploadPrompt`；
对这些类型执行安全取消只能证明取消路径，不能记为已支持上传。

## 固定版本与源码证据

调查以 `gradle/libs.versions.toml` 的 Mozilla Android Components **158.0.2** 为准。
已检查的固定源码缓存根目录为：

```text
C:/Users/tocml/AppData/Local/Temp/miru-ac-158
```

下表的 AC/Gecko 路径均相对此缓存根目录，行号对应本次检查的固定源码。

| 证据位置 | 已确认的行为及设计影响 |
| --- | --- |
| `concept-engine/mozilla/components/concept/engine/prompt/PromptRequest.kt:324` | `File` 提供 `mimeTypes`、`isMultipleFilesSelection`、`captureMode`，以及 single/multiple/dismiss 三类回调。选择结果必须调用真实 native 回调且只完成一次。 |
| 同文件 `:545`、`:516` | `Folder.onSelected(Context, Uri)` 是选择目录；`FolderUploadPrompt` 是随后独立的目录上传确认，不是同一请求的两种投影。 |
| `browser-engine-gecko/mozilla/components/browser/engine/gecko/prompt/GeckoPromptDelegate.kt:550` | Gecko File SINGLE/MULTIPLE 映射为 AC File；FOLDER 映射为 AC Folder。File 每个 URI 都经过 `toFileUri`，Folder tree URI 直接进入 `prompt.confirm`。 |
| 同文件 `:556`、`:586`、`:598` | Folder、File 的 native completion closures 在 `notifyObservers` 外创建。不同 observer 收到的 AC wrapper/uid 可以不同，实际回调身份共享。 |
| 同文件 `:996` | `onFolderUploadPrompt` 单独产生 `FolderUploadPrompt`，确认映射 ALLOW，取消映射 DENY。不能自动确认目录上传。 |
| 同文件 `:1083` | File 转换调用 support-ktx `Uri.toFileUri(context, dirToCopy = DEFAULT_UPLOADS_DIR_NAME)`；固定上传目录名为 `/uploads`。 |
| `geckoview/org/mozilla/geckoview/GeckoSession.java:6276` | Folder confirm 解析 tree URI并遍历目录，将 `filesInWebKitDirectory` 交给 Gecko。 |
| `geckoview/org/mozilla/gecko/util/IntentUtils.java:31`、`:312`、`:378` | tree 遍历保留相对路径，固定最大递归深度为 5；不能宣称任意深度目录都能完整上传。 |
| `feature-prompts/mozilla/components/feature/prompts/file/FilePicker.kt:52`、`:194`、`:223` | FilePicker 是 internal；旧 Activity result 根据当前选中 tab 的最后一个 File/Folder 找请求，不能直接满足 Miru 的迟到结果隔离。 |
| `feature-prompts/mozilla/components/feature/prompts/PromptContainer.kt`、`PromptFeature.kt:250` | PromptContainer 的 Activity 包装接受 `android.app.Activity`；公开 PromptFeature 另要求 AndroidX `FragmentManager`。 |
| `feature-prompts/mozilla/components/feature/prompts/file/FileUploadsDirCleanerMiddleware.kt` | 清理触发按 URL host 变化；不是每文档、每 tab 或私密 context 的租约清理。 |
| `feature-prompts/mozilla/components/feature/prompts/file/FileUploadsDirCleaner.kt` | 清理队列按文件名追踪且记录文件名；不能直接当作私密上传清理与无敏感日志保证。 |

support-ktx 源码未在上述解压缓存中；本次只读检查了官方固定版本
[support-ktx-158.0.2-sources.jar](https://maven.mozilla.org/maven2/org/mozilla/components/support-ktx/158.0.2/support-ktx-158.0.2-sources.jar)
内的 `mozilla/components/support/ktx/android/net/Uri.kt`：

- `:141` 的 `Uri.toFileUri` 总是复制，包括传入的 file URI。
- `:143`、`:149` 目标为 `cacheDir/uploads/<getFileName()>`；content URI 的名称来自 DISPLAY_NAME，经过文件名清理。
- `:164` 使用 `FileOutputStream(temporalFile)`，同名文件覆盖；同一多选内和不同请求之间均有碰撞风险。
- `:155` 至 `:160` 捕获复制错误后仍返回构造的目标 URI；调用方不能把回调返回等同于内容成功可读。

Activity 的保存状态风险以本仓声明的 AndroidX Activity **1.11.0** 为源码依据，
来源为官方 [activity-1.11.0-sources.jar](https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.11.0/activity-1.11.0-sources.jar)：

- `androidx/activity/result/ActivityResultRegistry.kt:245` 的 `onSaveInstanceState` 会把 pending result Bundle 写入输出状态（`:252`）。
- `androidx/activity/ComponentActivity.kt:291` 为默认 registry 注册 SavedState provider。
- pending result 可以携带选择器返回的 Intent/URI。只在业务代码中不使用 `rememberSaveable`，不能证明私密 URI 不进入保存状态。
- 实施前须记录实际解析到的 AndroidX 版本；若依赖解析已升级，重新核对该版本源码，不能将 1.11.0 的检查当作其他版本的运行证据。

## 接入边界

建议保持当前 Compose + `BrowserPrompts` 结构，新增应用级上传控制器和系统结果桥接。

“AC FilePicker 只接受 FragmentActivity”不是固定源码中的准确表述：FilePicker 和 PromptContainer 都是 internal，
PromptContainer 可包装普通 Activity；公开 PromptFeature 接收 Activity，却需要 AndroidX FragmentManager。
本仓 `app/src/main/kotlin/app/miru/browser/MainActivity.kt` 继承 ComponentActivity，不能直接提供 `supportFragmentManager`。
即便更改基类，AC FilePicker 按当前选中标签匹配结果的逻辑仍不满足本项目的 owner 约束。
因此不通过完整 PromptFeature 与现有 middleware 双重接管 BrowserStore。

可以参考 AC 的 MIME intent、capture hint、tree URI 和原生回调映射；应用自行实现 token、owner、
系统结果归属、复制验证、临时授权和清理。来源展示延续当前规则：File/Folder 类型没有发起 frame 源字段，
`origin=null`，单独显示当前 `topOrigin`，不能把顶层页冒充已验证的发起源。

## 所有权与生命周期

计划修改或新增的职责如下；新增文件名是建议，尚不存在已实现的上传控制器。

| 文件 | 后续职责 |
| --- | --- |
| `browser/engine/src/main/java/app/miru/browser/engine/BrowserPromptState.kt` | 增加 FILE、FOLDER、FOLDER_UPLOAD_CONFIRM 及最小展示字段；不投影 native callback、文件内容或长期 URI 映射。 |
| `browser/engine/src/main/java/app/miru/browser/engine/BrowserPrompts.kt` | 增加 supported/kind/project/answer 分支和 native callback identity；维持真实请求捕获、store wrapper 对应、token及完成一次语义。 |
| `browser/engine/src/main/java/app/miru/browser/engine/PromptOwnerMiddleware.kt` | 延续导航、关闭、切换、session/context替换等 owner 作废入口，并通知上传任务取消和租约清理。 |
| 建议新增 `BrowserFileUploads.kt` | 应用级选择器状态、单一 launched slot、IO任务、capture output URI、grant 和文档租约；不得保留 Activity 强引用。 |
| `app/src/main/kotlin/app/miru/browser/MainActivity.kt` | 挂接host、注册/路由系统结果、按 token启动一次；重建重新挂接同一进程内任务。 |
| `app/src/main/kotlin/app/miru/browser/BrowserPromptDialog.kt` | 文件/捕获入口、明确错误与取消、真实目录二次确认；私密面板继续纳入 FLAG_SECURE。 |

native identity 不能退回 wrapper 引用、data-class equality 或 `PromptRequest.uid`。
当前 `BrowserPrompts.nativeKey()` 已解决真实 Gecko 多 observer wrapper 的共享回调身份；
新增 File 可选共享的 `onSingleFileSelected`，Folder 用 `onSelected`，FolderUploadPrompt 用 `onConfirm`，
并以固定 delegate 源码和真实请求观测验证它们在各 wrapper 间共享。UI token 可以退休或替换，
仍存活的 native callback 不能因此被二次完成或错误取消。

系统任务应绑定 token、tab、EngineSession身份、document generation、private/context身份及 launched/abandoned 状态。
参考 `browser/engine/src/main/java/app/miru/browser/engine/BrowserPermissions.kt` 的
`markRuntimeRequestLaunched` 与 `onRuntimePermissionsResult`：

1. 启动前原子核验 owner并占用唯一 launched slot，再启动系统 Activity。
2. 系统结果本身没有 Miru token；只允许交给 retained slot，不查当前选中 tab 来推断请求。
3. owner 作废时结束网页请求、取消IO、标记slot abandoned；已经启动的slot保留至旧callback真正到达并排空。
4. 同通道的新任务不得抢占旧 launched slot。旧结果只撤销grant及清理，不回答新token。
5. 复制前后及 native答复前再次核验owner；每个路径只完成一次。
6. Activity重建不取消请求、不重新launch，保留同token/document及capture URI；进程死亡不恢复已经消失的native请求。

私密结果建议走独立、仅进程内的 ActivityResult registry/channel，并显式规划 MainActivity 的 request-code 路由与注册键。
该结构仍待实现验证，不能预先声称解决保存状态。不得把私密pending Intent/URI交给默认 SavedState provider，
不得保存到 Bundle、SavedStateHandle、preferences、Room或session；进程重启后的旧系统结果只清理。
必须在旋转、系统选择器覆盖Activity、Don't keep activities及进程死亡后实际检查Bundle与磁盘残留。

## 选择器与真实结果

| 类型 | 计划行为 | 完成条件 |
| --- | --- | --- |
| Single | ACTION_OPEN_DOCUMENT + CATEGORY_OPENABLE，最小临时只读授权；兼容 data和ClipData。 | 一个可读、校验完成的文件交给真实single回调。single返回多项时不得悄悄当成多选成功。 |
| Multiple | EXTRA_ALLOW_MULTIPLE，完整收集ClipData、去重并实行公开的数量/总量限制。 | 所有接受的文件完成验证后调用真实multiple回调；不把部分复制失败当整组成功。 |
| MIME | 空accept使用 `*/*`；单类型用type，多类型用EXTRA_MIME_TYPES，规范化扩展名与通配符。 | 系统过滤与网页实际File.type/name/size有证据；accept是筛选提示，不作为内容安全保证。 |
| Capture | image/video/audio分别使用真实相机/录音Activity；FacingMode为相机方向提示。 | 真实捕获内容回到网页且可上传；权限拒绝、无处理器或退回选文件不能计作capture通过。 |
| Folder | ACTION_OPEN_DOCUMENT_TREE，只读临时grant，调用真实Folder回调。 | 后续真实FolderUploadPrompt由用户确认后，网页收到完整、正确相对路径的目录内容。 |

capture 的 image/video输出使用新建的受限 FileProvider URI，存入进程内slot，支持 RESULT_OK 但data=null；
不能只依赖返回Intent。相机/录音权限与网页 getUserMedia 的站点授权分离，不能继承或写入错误的站点许可。
当前manifest已有CAMERA/RECORD_AUDIO声明，实施时仍须正确处理Android运行时授权与拒绝。

系统Back、RESULT_CANCELED、OK但缺失URI、不可读URI、没有处理器、IO错误、权限拒绝均须明确结束并清理。
已有网页File选择时，取消的新选择不得无依据地改变旧值；真实网页cancel/change行为由设备验收判定。

文件夹必须区分“系统选择目录”和“允许网页上传目录内容”两个阶段。
Folder第一次回调结束后仍需保存该文档的tree grant，直到后续确认完成及网页读取所需生命周期结束；
拒绝二次确认不得上传。超过固定Gecko深度/产品预算的目录不能静默截断后报告成功，应明确说明未完成。

## 暂存、同名文件与私密清理

上传需要新的 `Uri→File` 有界复制器，使用IO dispatcher、可取消循环、大小/数量预算、
已关闭stream、失败补偿清理以及owner revision复核。不能在主线程把大型provider内容直接交给AC同步复制。

必须解决固定engine的 `toFileUri` 行为后才能宣称完整支持：

- 每请求独立cache上下文/目录可隔离跨请求同名覆盖，但不能解决同一multiple回调内相同DISPLAY_NAME。
- 推荐评估固定engine小范围源补丁：已验证、位于请求隔离暂存目录的file URI可直接交Gecko，
  不再次按basename复制；外部URI仍须验证及有界复制。该补丁尚未实现，必须独立审查、构建及验收。
- 如果改用唯一暂存basename避免覆盖，会改变网页File.name；必须作为明确的产品取舍并验收，不能声称原名保真。
- 未解决同名内容覆盖、回调中的同步复制与失败后假成功前，不把单次普通文件成功扩大为完整上传能力。

成功的native callback不表示网页已经读取或发送完File。成功暂存按document/session持有租约，
不能callback一返回就删除；未交付文件、取消、IO失败和owner作废立即清理。
文档/session/context结束时删除对应租约并撤销应用持有的临时grant，应用启动清扫崩溃遗留的专属上传目录。
清理不能只看host变化或“最后一个私密tab关闭”；同源新文档、单个私密owner关闭及context替换也须覆盖。

以下既有实现仅提供参考，不能直接复用为完整上传方案：

- `app/src/main/AndroidManifest.xml` 只有 `ContextImageProvider`，没有AndroidX FileProvider与paths XML。
  `app/src/main/kotlin/app/miru/browser/ContextImageProvider.kt` 的 `openFile` 拒绝非 `r`，不能给相机写入。
  新provider只能暴露专属capture子目录，不能暴露cache根或files根。
- `feature/player/src/main/kotlin/app/miru/player/PlayerController.kt` 的URI读取包含IO、大小上限、取消及revision检查，可参考结构。
- `feature/downloads/src/main/kotlin/app/miru/downloads/DownloadDestination.kt` 是 `File→URI`，不是上传反向复制器。
- `feature/downloads/src/main/kotlin/app/miru/downloads/DownloadsScreen.kt` 的OpenDocumentTree会取得持久读写grant并保存下载目的地，不能照搬到临时只读上传。
- `feature/downloads/src/main/kotlin/app/miru/downloads/DownloadCoordinator.kt` 和DownloadDatabase持久保留下载记录；上传URI、源、内容与租约不能进入该队列/数据库。
- `app/src/main/kotlin/app/miru/browser/MiruApplication.kt` 的私密tab观察点可参考，但上传必须追踪自身的document/session/context所有权。

普通/私密分别使用内部cache专属随机目录；不持久保存源、原始URI与文件名映射，不记录认证、内容、私密源或用户文件名。
外部相机/系统provider自身的数据行为不能被描述为Miru已清除；验收需区分应用暂存、URI授权与外部应用产物。

## 真实设备验收门

建议新增 `app/src/androidTest/kotlin/app/miru/browser/FileUploadIntegrationTest.kt` 与loopback multipart服务端，
通过真实HTML `<input type=file>`、系统DocumentsUI和真实capture Activity触发请求。
文件可用合成fixture，但不得直接构造PromptRequest、制造Activity结果、注入网页FileList或替换native选择回调。

| 验收门 | 必须保留的真实结果 |
| --- | --- |
| Single / Multiple | 系统选择真实文件；网页File.name/type/size与服务端收到的字节哈希一致；多选无丢项、无重复完成。 |
| MIME | 无accept、单MIME、混合MIME、通配符、扩展名覆盖；记录系统筛选和实际选中内容，不仅检查intent字段。 |
| 同名与并发 | 不同请求同名文件、同一多选同名DISPLAY_NAME内容均不覆盖；若需测试DocumentsProvider，仍由系统UI真实选择其内容，不伪造result。 |
| Capture | 实际拍照/录像/录音、方向提示、OK但data=null、取消及权限拒绝；网页读取与上传哈希/内容证据成立。需要真实硬件的分支在物理机验收。 |
| Folder | 目录选择后出现独立确认；确认后正确文件集合与relativePath上传；拒绝为零上传；嵌套、空目录、权限失败及固定深度边界明确。 |
| 系统取消与错误 | Back、无URI、不可读provider、无处理器、预算超限均结束且清理；网页原有选择保持/取消事件符合真实行为。 |
| Activity重建 | picker或capture未返回时重建；同token/document、只launch一次，正确结果回到原owner且服务端只收到一次。 |
| 迟到结果 | picker显示期间真实导航、关tab、切tab、session/context替换；返回旧结果只排空旧slot，不完成新请求、不泄漏文件。 |
| 私密清理 | 私密取消、成功后关owner、最后context关闭、IO中途作废、崩溃/进程重启后，应用上传目录与grant按设计清除；不关闭FLAG_SECURE取证。 |
| 保存状态与持久化 | 检查日志、Room、preferences、session、Bundle/SavedState与cache；证明原始URI、私密源、文件内容/敏感文件名没有进入禁止的持久位置。 |

运行前记录源码commit、固定依赖及实际解析版本、APK SHA-256、设备serial/API/ABI、provider/camera版本与完整命令。
保留原始instrumentation输出、网络计数与合成fixture哈希；真实用户文件和私密内容不得进入诊断。
先独立验收再进入回归，不能用专项通过覆盖整套回归失败。

**完成标准：** 对声明支持的每种选择方式，用户能在真实系统界面选中，网页收到正确File/目录内容，
实际网络上传正确字节，同时取消、重建、迟到结果和私密清理门通过。
仅显示UI、出现StateFlow或执行安全取消，都不满足该标准。

## 2026-10-25 补充静态审查：线程与 native bridge

本节仅记录固定依赖源码的后续方案审查。文件上传仍未实现，本轮没有为这些候选方案修改生产代码、
编译验证或运行设备测试。已有页面 prompts 的构建、专项与回归结果不构成文件上传验收。
前文的小范围 SDK 源补丁是备选，尚不能据此断言文件上传必须修改 SDK；应先评估下述 native adapter
与版本锁定的兼容层，并分别保留它们的边界与验证证据。

### 回调的线程约定

下列路径仍相对于前述 `miru-ac-158` 缓存；support-ktx 行号对应官方 **158.0.2** sources jar。

| 证据位置 | 已核实结论 |
| --- | --- |
| `concept-engine/.../prompt/PromptRequest.kt:328`、`:329` | File 的 single/multiple 字段只是函数类型，没有赋予实现跨线程调用的许可。 |
| `browser-engine-gecko/.../prompt/GeckoPromptDelegate.kt:586`、`:598` | 回调在调用线程同步执行 `toFileUri`，随后直接执行 native `prompt.confirm`，没有自动切换 dispatcher。 |
| `geckoview/.../GeckoSession.java:6261`、`:6275` | FilePrompt 的两个 `confirm(Context, ...)` 重载均标注 `@UiThread`。 |
| 同文件 `:5040`、`:5102` | BasePrompt.confirm 与 isComplete 也标注 `@UiThread`；完成状态和结果不是由 GeckoResult 的锁保护。 |
| `geckoview/.../PromptController.java:53`、`:66` | native prompt 完成会同步从 PromptStorage 的 HashMap 移除请求。 |
| `geckoview/.../GeckoResult.java:181`、`:893` | GeckoResult 本身为 `@AnyThread`，complete 是 synchronized；这不放宽 FilePrompt 的 UI 线程约定。 |

上述 FilePrompt 完成调用链没有 `ThreadUtils.assertOnUiThread()` 硬断言，但缺少断言不等于线程契约允许。
不能把整个 AC 文件选择回调放到 IO dispatcher，再以“运行时没有抛线程异常”作为安全证据。
可取消的 provider 读取、预算控制和暂存校验应在 IO 完成，native prompt 的核验、confirm/dismiss 与完成一次
状态应回 Main 串行处理。

### 动态 cacheDir 可以隔离路径，但不能替代复制方案

固定 support-ktx `Uri.kt:143` 每次 `toFileUri` 只读取一次 `context.cacheDir`；后续使用已构造的目录和目标 File，
不再次读取 cacheDir。multiple 回调逐 URI 调用该 helper。因此，若 ContextWrapper 每次 getter 返回不同的、
已存在的随机子目录，固定实现可以隔离同一 multiple 内的同名文件，也保留其经过现有 sanitize 处理的 basename。
这比“每请求返回同一个隔离目录”多解决了同批同名碰撞。

native `GeckoSession.java:6315` 对转换后的 file URI 只取 `uri.getPath()`，不会再次访问 cacheDir、比较 Context 身份，
也不会把 Context 留作后续文件读取依据。该路径隔离技巧在固定源码上成立，但回调仍会同步复制、吞掉复制异常后
返回目标 URI，且 native confirm 已可能把文件交给网页；事后检查暂存成功不能撤回已经交付的结果。
因此它不能单独满足主线程复制、可取消 IO、失败不交付和私密租约的验收门。

### 公开 render adapter：可覆盖前台交互，仍有首次 render 前的窗口

`GeckoEngineSession.kt:118` 的 native `geckoSession` 属性是 Kotlin `internal`，没有可直接使用的公开 Kotlin getter。
但 native `GeckoSession.java:3682`、`:3692` 的 promptDelegate setter/getter 是公开 API；EngineSession 也公开实现
Observable，可调用 `notifyObservers`。取得真实 native session 后，可以用 `GeckoSession.PromptDelegate by original`
保留原 AC delegate，仅覆盖 `onFilePrompt`，构造一次共享的 AC File 请求，在应用 IO 暂存校验完成后回 Main
直接调用 native FilePrompt.confirm，避免再进入 AC 的同步 `toFileUri`。

公开 view 路径需要准确描述：`EngineView.asView()` 只是把实现转为 View；固定 GeckoEngineView 是 FrameLayout，
其子 View 才是 NestedGeckoView。可以在受控的 `render(actual EngineSession)` 返回后，通过公开 ViewGroup API
遍历唯一的 GeckoView，再调用公开的 `GeckoView.getSession()`，以实际 EngineSession 身份建立配对。
不能根据当前 URL、selectedTabId 或猜测的 native session 来配对。该过程需要包装所有实际 render 路径，
包括直接 render 的扩展 popup；桥接器不能持有 Activity 或 View 的强引用。

固定 AC 只在 `GeckoEngineSession.kt:1777` 的初始化流程中，于 `:1803` 设置原 promptDelegate；正常 render 和
Activity 重建不会重设它。安装应幂等，并按 engine/native session 身份保留到 session 生命周期结束。
自有 native PromptInstanceDelegate 应把系统 dismiss 转发为对应共享 AC 请求的 `onPromptDismissed`；其余 prompt
方法保留原 delegate。当前 native update 文档描述的是 Choice 更新，不能据此宣称 File 具有已验证的更新协议。

这一方案可覆盖已 render 的普通、私密及扩展创建页面中的用户交互，却不能证明首次 render 前不会产生合法请求。
Gecko 的文件入口检查用户激活和 BrowsingContext，没有给 Android embedder 提供“必定已 render”的保证。
没有用户激活的普通 onload click 会被 native 阻止，但不能据此排除所有提前或特权调用。
未桥接请求若只能取消，必须作为范围边界记录；不能把它扩大为所有早期请求都已支持，也不能为追求容易测试
而默认缩减产品原定范围。

### 未 attach 的 probe：存在副作用，不能未经动态验证直接上生产

另一个候选是在 Link 中、首次 load 前，用从未 attach 的独立 EngineView 同步 render 新 session，取得 native
引用并安装 adapter，随后立即在 finally 中 release，再让真实 view 接管。固定源码未显示必须先 attach 的限制：
`GeckoView.java:596` 只在 attached 时取得 display；全新 probe 没有有效 surface，正常情况下不会使 session
active/focused，`releaseSession` 也不关闭 session。未 open 的 session 由 `defaultColor():488` 的分支处理。

它仍然会设置 session owner，并触及 IME、accessibility、magnifier、haptic、autofill、print、overscroll、
first-paint callback 和 clearColor。release 清理 probe 的引用，不等于恢复所有旧的自定义值。
尤其 `GeckoSession.java:3450` 的 setOwner 会主动释放已有 owner；“不在 bridge map”不证明该 session 从未显示。
若继续评估，只能在证明是尚未被真实 view 持有的新 session 时执行，覆盖所有 view 的 render 身份登记，
整个流程不得 attach、requestFocus、调用 view 的 onStart/onResume 或中途 yield。
这些静态条件不足以把 probe 作为默认生产方案；其输入、显示、delegate 与旧 owner 副作用仍须动态验证。

### 版本锁定的 internal ABI 兼容层也是待评估选项

还可评估版本锁定的 SDK internal bridge。若后续选择通过 Java 直接调用
编译产物中 Kotlin internal 属性生成的 mangled getter，应明确标为 **固定版本的 internal ABI 兼容层**，
不能称为官方 public API，也不能仅凭源码属性名猜测实际 accessor 的 module 后缀。

采用前须检查实际解析 AAR 的 getter 名称、签名与可见性，完成真正的编译验证；在运行中核验实际 EngineSession
类型、native 配对、原默认 delegate 的存在及身份，覆盖普通、私密、window.open、WebExtension 新 session、
重复 Link、session 替换和 Activity 重建。必须回归所有现有 prompt 类型与其取消/生命周期行为，不能只证明
File 单次成功。依赖升级需重新审查该兼容层，不能静默落回错误 native session 或原 AC 同步复制回调。
本轮没有实现、编译或运行验证这一 ABI 方案。

若上述集成路线最终仍不能满足范围和验收门，再评估小范围 MPL 源补丁。固定 AC 的最小候选点是
`GeckoPromptDelegate.kt:1083` 的 URI 转换 helper：仅让已由应用在 IO 完成验证、位于受控 app-private 暂存根内的
file URI 直达 native，避免再次复制；不能无条件放行任意 file URI。该备选同样尚未实现或验收。
