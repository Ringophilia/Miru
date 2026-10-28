# B13 拍照与录像上传（0.5.8）

生产与测试基于`ae73654`后的本批源码，versionCode16 / 0.5.8，API35/x86_64受控只读模拟器`emulator-5598`。
全B13、录音、目录、物理机及发行门继续待验。本批不使用伪造ActivityResult、FileList、PromptRequest或native callback。

## 系统处理器与真实界面

`057-capture-handler-inventory.txt`实际解析到stock `com.android.camera2` 的IMAGE_CAPTURE / VIDEO_CAPTURE；
`057-capture-audio-handler-mime-inventory.txt`对无type及audio/*、3gpp、amr、mp4均没有RECORD_SOUND处理器。
未把缺失处理器的安全取消或文件选择回退算作录音成功。
AVD后摄为emulated、无前摄，launcher为no-window/no-audio；没有物理镜头或真实前摄成绩。

手动SDK UI发现只核对Camera2控件与review：shutter_button、recording_time、done_button、cancel_button、retake_button；
对应`camera-*-ui.xml`与`video-*-ui.xml`保留。它们没有通过Miru传入output，不能算Miru上传证据。

## 真实上传闭环

`FileUploadIntegrationTest`新增两项：真实HTML accept=image/*或video/*、capture=environment，
实际Miru拍照/录像按钮，真实Camera2拍摄、录制时钟至少2秒、停止与review Done。
实际launch的EXTRA_OUTPUT/ClipData一致、读写flags与back-facing hint均核对；没有用静态provider图像替代拍摄。

PhotoModule可能到Done才写output。测试在Done前只读打开实际EXTRA_OUTPUT原文件，保持原inode的FD，
Done与网页上传后从原FD验证JPEG结构/尺寸与原字节SHA；不读取staging冒充源，不延迟生产清理，FD始终finally关闭。
录像在review及Done后用原FD的MediaExtractor验证视频轨、尺寸、正duration和样本，再核对原字节。
两项都检查网页File.name/type/size与服务端真实POST字节，随后capture原文件/receiver消失、
Camera2实际UID的READ/WRITE由有权变无权，成功document lease仍保存相同字节，关闭owner后再删除。

## 构建与结果

- `058-camera-first-build.log`主/测试APK、JVM与lint成功（2分45秒）；JVM141项，无失败/错误/跳过，lint0错误/22警告。
- `058-camera-first-device.log`拍照/录像独立 **2/2（46.552秒）**。
- `058-upload-complete-device.log`原8+MIME5+capture2，整类 **15/15（170.368秒）**。
- 每轮input/result/runtime日志独立保留，不把这些专项拼成全套回归通过。

固定主APK SHA-256 `e1be264beb62f9c0002a7be995d4b6b10ed8ab1109b528cc711242742f0faad9`，
测试APK SHA-256 `1573cb83c84d531a8b3d2170e296b786cfbd1138925ccfb792b0f62d52247970`，
二进制保存在`058-camera-first-binaries/`，完整命令见`058-camera-first-input.json`及`058-upload-complete-input.json`。

## 真实捕获生命周期专项

在生产提交`924c97a`之后仅扩展测试，主APK仍为上述`e1be…aad9`。
`058-capture-lifecycle-build.log`测试APK构建与lint通过（1分47秒）；
`058-capture-lifecycle-first-device.log`三项 **3/3通过（65.015秒）**：

- 先由真实DocumentsUI选PNG，再拍照并在Camera2 review取消；网页再次POST的仍是原PNG元数据与字节，change仍只有一次；新capture文件、整个输出租约和外部UID读写grant删除，旧文档租约保留至关页。
- 真实录像到review后关闭原owner，先验证文件/租约与grant回收、native请求退休，但只含opaque tombstone的receiver仍等待；真实Done返回后丢弃旧结果并排空slot。预先加载的下一owner实际FileList为空，随后自己的DocumentsUI选择与POST正常。
- Camera2录制至少两秒时真实重建MainActivity与UploadPickerActivity；要求两个新实例、原token/URI/FD/grant、原网页单次GET与继续增长的录制时钟，真实Stop/Done后只有一次网页交付且字节一致。

没有注入系统结果或延迟生产删除。输入、运行日志、终态分别见同前缀`-input.json`、`-runtime.log`、`-result.json`；
测试APK保存在`058-capture-lifecycle-first-binaries/androidTest.apk`，SHA-256
`469015d65347cf1b4a39ba8736c7117f892d92bd0ad524c0e86834739be3629d`。
原15项测试保留；同一新测试APK随后运行上传整类，
`058-upload-lifecycle-complete-device.log` **18/18通过（199.276秒）**，
输入与终态见同前缀`-input.json`和`-result.json`。这是同一次18项真实执行，不是拼接独立轮次；
后续常规完整回归见下；排除类及发行运行门仍待。

DEBUG结果日志只记录布尔/计数，没有URI、文件名、Intent对象或私密值。
实际拍照为`dataIntentPresent=true, dataUriPresent=false`，录像两者均true；
不能把这轮写成“RESULT_OK且整个Intent为null已设备通过”。代码使用预建output回退，但空Intent分支仍缺相应真实handler证据。
照片review时原output确为0B，Done后的原FD和网页字节一致，原FD证据路径已实际覆盖。

## 取消与剩余门

接收器Cancel和Back均交给manager决定结束，不再无条件finish绕过等待旧系统结果的receiver保留；
取消中的操作仍先退休native请求/IO/文件与grant，已启动OS通道仅保留opaque tombstone至真实结果排空。
整类原取消/旧结果及私密清理通过；上述专项又覆盖普通拍照review取消、录像review关owner迟到结果和录制中重建，不扩大为所有handler/生命周期、进程死亡或私密捕获已完成。

后续仍需CAMERA拒绝/永久拒绝、完整取消/重建/迟到结果矩阵、进程死亡与私密捕获、
其他相机/录音handler、空Intent、前摄与物理机、R8/ARM64运行及排除类；目录与其他页面提示另列。

## ARM64开发包构建与打包

源提交`dbddaed`（生产仍为`924c97a`，后续只有测试与文档），
`058-release-build.log`的R8/shrink release构建成功（2分50秒）。
原AGP包`058-release-binaries/arm64-release-stock.apk`为111,164,037字节，SHA-256
`2ca8b4ba73bf3513388933e5bb67750e5ea4849fd3610816e7845f2fc59eac24`。

同一固定工具链输出`artifacts/releases/Miru-0.5.8-arm64.apk`，**108,011,752字节**，SHA-256
`bf92e7a4f4b69893f25632e4057b5d80f7777a27f3fbcc27a58a541c1ecd062c`。
同名`.verification-20261027-215748-0e1e30ab/pipeline.json`记录1518个payload逐项完整、
SDK对齐、签名与原Android Debug证书一致，严格小于110,000,000字节。
`058-release-native-verification.json`检查全部17项原生载荷均为ELF64/AArch64；
`058-release-omni-verification.json`验证4077项Gecko原payload保持、GEO/导航两项精确修改和5项官方新增。
omni仍为16,459,282字节、SHA `f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`。

这些是本版构建/打包证据；正式签名、R8实际运行、ARM64实际运行及完整发行验收继续待验。

## 固定0.5.8常规完整回归

原受控5598进程已确认不存在后，另启API35/x86_64只读实例`emulator-5600`，
安装前实际查询没有Miru；仅安装同一E1BE主APK和469015测试APK，未修改生产或测试源。
`058-full-ready-discovery.log`只列出117项（0.506秒），不计执行通过；该清单运行可能已初始化应用，
因此本轮不单独充当“uBO首次应用启动”专项。

`058-full-device.log`实际一次执行 **117/117通过（907.736秒）**，
输入、终态及运行日志见`058-full-input.json`、`058-full-result.json`、`058-full-runtime.log`。
源提交`30b9f42`，主目录在开始时干净；运行中无Gradle/R8、录屏或其他设备驱动并发。
包括原上传8、MIME5、拍照/录像2及捕获生命周期3，和其余浏览器/权限/页面提示/下载/媒体/离线/超分用例。
0.5.7整套失败的两个浏览器用例及QUALITY benchmark本轮均通过；旧失败不删除，单轮通过不证明历史超时根因已消失。

三档benchmark实际完成60有效帧，平均FAST202.9、BALANCED271.6、QUALITY477.3ms/帧，
四条Anime4K/FSR渲染链也完成60帧，故不能据本轮推定物理GPU性能门已通过。
仍按既有边界排除`DownloadRestartTest`、`OfflineRestartTest`和需独立fixture的
`RuntimeSitePermissionIntegrationTest`；前两类重启、真机/发行门仍须各自证据，当前版本系统权限另见下节。

## 独立首次启动与真实系统权限

另启API35/x86_64只读实例`emulator-5602`，启用虚拟音频并在安装任何应用前通过实际console
`avd hostmicoff`获得OK（`058-audio-hostmic-off.log`）；本节不验证物理拾音。
安装前无Miru，安装固定E1BE/469015包后确认目标进程尚不存在，未先跑清单发现。

- `058-startup-fresh-device.log`真正首次目标进程运行uBO专项 **1/1（36.811秒）**，真实默认规则、网页请求与全局切换；early handoff本轮仍为present=false，不能扩称竞争分支已覆盖。
- 随后新的instrumentation进程独立运行`RuntimeSitePermissionIntegrationTest`，
  `058-runtime-fresh-device.log` **3/3（33.353秒）**：真实系统摄像头拒绝、摄像头/麦克风允许仍要求网站同意、仅coarse的近似定位。
  类自身断言权限从未授予开始，真实点击PermissionController，无pm grant、预授权或伪造回调。

每轮同前缀input/result/runtime日志独立保留，不将1+3拼入常规117计数，也不替代系统永久拒绝、
完整私密/重建/迟到结果、真机、旧GEO持久化迁移或两类外部重启验收。
