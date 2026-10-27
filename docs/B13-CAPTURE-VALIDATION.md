# B13 拍照与录像上传（0.5.8首批）

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

DEBUG结果日志只记录布尔/计数，没有URI、文件名、Intent对象或私密值。
实际拍照为`dataIntentPresent=true, dataUriPresent=false`，录像两者均true；
不能把这轮写成“RESULT_OK且整个Intent为null已设备通过”。代码使用预建output回退，但空Intent分支仍缺相应真实handler证据。
照片review时原output确为0B，Done后的原FD和网页字节一致，原FD证据路径已实际覆盖。

## 取消与剩余门

接收器Cancel和Back均交给manager决定结束，不再无条件finish绕过等待旧系统结果的receiver保留；
取消中的操作仍先退休native请求/IO/文件与grant，已启动OS通道仅保留opaque tombstone至真实结果排空。
整类原取消/旧结果及私密清理通过，不扩大为所有捕获取消、捕获中重建、进程死亡或私密捕获已完成。

后续仍需真实capture取消保留原FileList、review中关owner的迟到结果、录制中重建、CAMERA拒绝/永久拒绝、
其他相机/录音handler、空Intent、前摄与物理机、R8/ARM64运行和完整回归；目录与其他页面提示另列。
