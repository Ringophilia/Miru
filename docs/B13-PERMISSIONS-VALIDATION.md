# B13 权限首批：开发与验证记录

应用0.5.4/code12开发中，设备为API35/x86_64只读模拟器；真实系统权限在全新实例5588失败，修复后5590通过。
旁听监听器修复后的站点专项、后续真实系统权限及最终源码站点复跑各有通过终态。本文只覆盖摄像头、麦克风、
定位及对应本站权限/清理能力，**不是整个B13或最终验收通过**。文件选择、页面prompts、
通知展示、弹窗、外部协议、完整证书/站点数据面板等继续在全量矩阵中保留。

## 请求与存储边界

- 保留ComponentActivity与唯一BrowserStore。基于固定AC158.0.2的PermissionRequest和
  request/consume契约做小范围MPL适配，使用Miru Compose面板；没有声称直接实例化未接入的
  SitePermissionsFeature或引入它的完整UI依赖。准备与上游陷阱见B13-IMPLEMENTATION-NOTES.md。
- 在AC启动session之前注册实际EngineSession观察者，捕获请求对象的tab/session/文档generation；
  与store中的canonical request关联后一次终结。切标签、导航、关闭、session替换时拒绝旧请求；
  系统权限结果只交回原inflight，不重新查询当前tab投递。Activity重建重新绑定弱host，私密状态不进SavedState。
- 使用完整HTTP(S) origin区分scheme/host/port。普通选择存独立SQLite；私密选择只在内存，
  最后私密标签关闭后清空，并通过generation阻止异步native查询结束后回填旧私密记录。
  Android权限与网站许可分别判断；coarse或fine任一满足定位，不把Android拒绝写成网站BLOCK。
- 本站设置修改后重载同origin/private/context的已加载文档；B20 SITE_SETTINGS在同一决策互斥下
  清普通/私密/临时账本、等待Gecko回调，并重载已加载网页。现有媒体流是否确实停止仍需相称设备取证。

## Gecko定位持久化的实际来源证据

固定AAR中的GeckoViewPermission.sys.mjs在内容/Android许可后将结果写为EXPIRE_NEVER。
仅在Miru内存保留“本页允许”，再异步清native cache，无法保证下一文档不会绕过ASK。
因此构建时只修改该模块的geolocation持久化写入，其他类型和原有授权/Android检查保持；
原件、修改源码、精确摘要和MPL保留在third_party/mozilla-permission-policy/。
启动在创建/恢复session前枚举旧native GEO记录、设为PROMPT并回读；失败保持加载门关闭。
应用自身的站点写入也不重新设置native ALLOW/DENY。
该构建overlay批次已提交为`f3117fb`；应用权限协调器、旁听监听器及后续运行时修复尚未整批提交。

归档现为4078项原payload保持、1项固定模块精确修改、5项官方blocklist新增，
不能沿用0.5.3“4079全部保持”的产物声明。
`artifacts/b13-policy-overlay/standalone-tests.log`：46项Java检查通过；
`permission-policy-js-tests.log`：实际原/改模块的22个本地stub场景通过。
这些结果不证明完整C++缓存路径或实际设备行为。

## 已运行的构建与失败保留

- `054-permissions-debug-build.log` 首次构建因测试引用未引入的Espresso而失败；改用应用真实返回分发后，
  `054-permissions-repaired-build.log`成功（86秒）。JVM XML共111项，0失败/错误/跳过。
- `054-debug-omni-verification.json`验证该轮Debug包4078+1+5全部符合固定输入；
  omni SHA-256为`2a8da1a2c185d24f11e385e794b18b5d169a4e5afa64ce7f8882f8af33b14cb1`。
  该轮Debug APK SHA-256为`8ea27d1732ad22c12ad428db0c3798e72ad8958a8e0da149238c59a843198515`，
  不用于证明后续源码改动或ARM64发布体积。
- `054-permissions-device.log`：5项3通过、2失败（83.752秒）。切标签迟到回答、同源文档替换、
  普通定位BLOCK/重置与端口隔离通过。私密用例在截图颜色等待处超时；相机/麦克风点击允许后实际返回NotAllowedError。
- `054-permission-diagnostic-build.log`成功（107秒）。私密夹具改用网页自动定位请求；
  `054-permission-diagnostic-device.log`仍为2项均失败（76.445秒），保留原文。
  其中自动请求用例不应先等待loading=false，已改为先观察原生prompt再回答。
  当时阶段日志记录原请求归属、Android许可与终结前allow=true，但宿主窗口没有焦点；
  这些日志不能证明Gecko回调仍未被其他监听器提交，后续直接状态取证已纠正这一判断。
  随后加弱host焦点绑定，等待面板/系统窗口退出且宿主窗口重新获得焦点后才grant。
- `054-permission-focus-build.log`成功（77秒）；`054-permission-focus-device.log`为
  **2项1通过、1失败，55.357秒**。私密重置不删除普通同源选择、B20清理后重新询问的用例通过；
  相机/麦克风仍返回NotAllowedError。等待宿主焦点并未解决该失败，当时仍需继续取证，
  不能把该焦点假设写成已证根因或已修复。后续根因及通过结果见下一节。

## 旁听监听器提前拒绝：根因与修复

`054-media-details-device.log`新增临时定位ALLOW后同源新文档重新询问的用例，7项为5通过、2失败（84.939秒）。
定位ALLOW也被拒绝；媒体异常时document.hasFocus=true、visible、secure=true、用户激活有效，
Gecko子视图/宿主焦点正常，视频/音频各1个原始来源且ID均非空。不能继续把失败归因于宿主焦点或空设备ID。
`054-native-callback-device.log`显式传入原请求permissions后仍失败，回调方法正常返回；默认参数分发不是根因。

仅对两个普通合成夹具开启的`054-console-trace-device.log`给出直接状态：用户回答前，
定位Content已completed=true，Media的原生callback已经被提交（mCallback为空）。
固定AC158.0.2的EngineSession.Observer两种权限默认方法均直接调用reject；
菜单和uBO导航旁听监听器没有覆盖该默认值，因此在协调器等待用户回答时抢先拒绝。
该行为同时经固定class字节码与实际回调状态核实。之后的grant虽然返回正常，实际已经无法改写结果。

新增PermissionNeutralObserver，最终覆盖两种权限方法为空实现；仅菜单和uBO导航两个旁听监听器使用。
BrowserPermissions保留实际捕获/终结职责，未受其管理的uBO popup及惰性表单预建context仍保留默认拒绝。
没有全局关闭权限检查或伪造媒体/定位结果。

`054-neutral-observer-build.log`构建/engine JVM成功（45秒）。
`054-neutral-observer-device.log`两个原失败专项**2/2通过，21.221秒**；
`054-neutral-observer-stages.log`在修复后的07:17轮确认用户回答前定位completed=false、
媒体nativeCallbackPresent=true；与修复前completed=true、nativeCallbackPresent=false形成直接前后对照。
之后真实音视频轨道为live；实际定位回调没有权限拒绝，临时授权后的下一同源文档重新询问。
定位夹具允许真实提供方成功、不可用或超时，不将后两者写成已取得实际坐标。
关闭诊断参数后，`054-neutral-final-site-device.log`**7/7通过，61.002秒**，
包含全部6项现有站点权限用例和混合下载列表/撤销/停源播放用例。
其中私密重置、普通同源选择保留及清站点设置后的重新询问通过；不是完整私密生命周期审计、全套回归或整个B13验收。
该站点策略套件预先授予Android权限，不能代替真实系统允许/拒绝专项。
这轮7/7发生在后述Media系统权限早拒绝及NativeGate归属修复之前；最终源码另有下述独立复跑记录。

诊断反射只读回调状态；临时引擎console仅允许两个普通夹具和Debug包，测试结束先恢复原值再清理夹具。
常规运行不启用该参数，应用默认consoleOutput=false不变。

## 真实系统权限：失败、修复与通过边界

系统弹窗初轮`054-runtime-permissions-device.log`首先卡在未推进Compose测试时钟的等待，后续两项因前置步骤失败而失败；
改用同期限compose.waitUntil后，`054-runtime-clock-device.log`进入了真实系统请求，但节点查找仍失败。
Google安装包名与AOSP资源命名空间分开校验，期限、真实点击和原始系统权限断言保持。

全新实例`emulator-5588`的输入记录为`054-runtime-fresh-input.json`。
`054-runtime-fresh-device.log`为**3项均失败，50.323秒**：首项已真实点击Android摄像头拒绝，
但Gecko仍继续发送Media内容请求，Miru又显示网站面板，未回答的夹具保持`Runtime pending`；
后两项在顺序前置检查失败，不能据此判断真实允许或近似位置已执行并失败。
此前所有失败日志保留，不用站点套件7/7覆盖本轮系统权限失败。

当前工作区已在Media分支读写网站选择及显示网站面板前检查Android权限；未满足时直接拒绝原请求，
不重开系统请求、不把Android拒绝写成网站BLOCK，也不删除已有网站ALLOW。
该检查只覆盖已支持的camera/microphone来源；定位仍保留网站许可后再请求Android许可的顺序。
NativeGate同时持有原pending，启动前重新核对归属；迟到结果只排空原槽，
不在替换后的标签显示系统设置提示；最终grant前重新核对Android权限。
`054-runtime-owner-final-build.log`的Debug构建与engine JVM检查成功（33秒），
后续在全新`emulator-5590`运行整类真实系统权限专项，输入记录为`054-runtime-denial-input.json`，
`054-runtime-denial-device.log`为**3/3通过，21.741秒**。
该轮没有伪造回调、pm grant或系统预授权：实际点击PermissionController摄像头拒绝，
确认网页拒绝且网站选择仍为ASK；实际分别点击摄像头/麦克风允许，仍需网站许可，网站BLOCK继续阻止捕获；
实际选择近似定位后coarse=true、fine=false，允许的网站定位回调没有权限拒绝。
系统按钮按Google安装包与AOSP资源命名空间分别校验所属窗口，不放宽真实点击或系统权限断言。
该输入记录的Debug APK SHA-256为`0186a815febce1e9534ed60afc6ac6c70832ac928f42af5d39829c5d44f841be`，
仅对应本轮专项，不代表正式签名、发布包或完整回归通过。

包含Media系统权限早拒绝、NativeGate原pending、启动/结果UI归属及grant前系统权限复查的最终production源码，
随后在未启用trace时复跑`054-owner-final-site-device.log`：**7/7通过，46.626秒**。
覆盖同一6项站点权限和混合下载撤销/播放，补齐最新修复之后的专项回归；
此前61.002秒的7/7和全部失败记录仍保留，不能据此扩大为全量回归或release验收。

## 仍需证据

系统永久拒绝、旋转/取消/迟到结果及完整生命周期矩阵；
旧native GEO迁移和跨进程普通选择保持；
私密完整origin隔离与最后私密context关闭；设置变更后的真实活动媒体停止；
新版release/lint/体积与常规回归。最终源码站点/混合下载7项和真实系统权限3项通过不等于首批全部完成，
0.5.3的77/79与107,897,008字节打包证据不能当作本版回归或发布证据。
