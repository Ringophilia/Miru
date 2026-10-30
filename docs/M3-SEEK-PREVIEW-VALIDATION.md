# M3 进度条缩略图验证（0.5.11/code19，验收进行中）

本批实现 §8.4 / P6 的拖动缩略图：10秒分桶、最多60张当前来源的内存LRU、单个活动提帧加
最新待发目标。没有图片时只显示目标时间，有图片才显示预览；松手才seek，真实Cancel不seek。
完整常规回归已按下文验证；本版release打包仍待，不能据已有专项关闭整个M3/M4。

## 实现与规范方案

使用固定 Media3 1.11.1 官方 `FrameExtractor`，复用其单个视频ExoPlayer和串行提帧管线，
作为原方案中手写MMR/ImageReader组合的统一实现。没有将需求缩成仅本地：HTTP MP4、content
URI、HLS TS/fMP4/AES128、DASH均须经过真实解码/图像/时间戳验证。此实现差异明确保留，
不宣称采用了MMR或ImageReader。底层API预验及其真实失败见
[候选预验](M3-SEEK-PREVIEW-PREFLIGHT.md)，裸future取消/close不是生产取消链。

`PlayerController`通过`PlaybackSourceSession`共用主播放/预览的来源和缓存规则；每份预览
lease仅登记自己的Call和DataSource，主动中断挂读后撤销future并释放提帧器，不取消主播放。
HLS清单走upstream，媒体分片可读流缓存；offline工厂不设HTTP upstream，private不取得流缓存。
普通主播放仍沿用10秒读超时，预览链为30秒；取消验收要求在更早的原5/6秒期限内真正释放。

当前headers在session建立时冻结，跨源Cookie/Authorization剥离。预览401/403只触发一次
刷新，换预览代次和LRU并重发当前拖动目标，不重建主player或改变其sourceRevision/播放位置。
open/主源切换/release会撤旧预览；隐藏、后台、PiP、锁定及取消手势也撤预览。

预览缩小后复制为自有Bitmap，缓存/UI只释放引用，不回收库仍持有的借用图像。仅内存缓存，
不保存图片或凭据。普通流缓存键新增`miru-stream-v2`命名空间：长度前缀编码headers，避免
旧逗号/等号拼接的映射碰撞。旧流缓存条目不再命中，保留文件自然LRU淘汰；离线工厂和已下载
离线字节不改。真实红/蓝TS反例检验分区，不能只以digest不同作为隔离证据。

## 首轮固定输入和构建

`artifacts/m3-thumbnails/0511-build-first/`：源基线`75498d6`加未提交实现，516项源码构建前后
无变化。`:feature:player:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
:app:lintDebug --no-daemon`成功，7分35秒，播放器JVM **27/27**、0失败/错误/跳过，lint0错误/
22警告。其中Cache3、Controller14覆盖60张淘汰、快速目标合并、迟到factory/结果、跨代次错误
交付及恢复，另10项为既有播放策略/字幕。

| 输入 | 字节 | SHA-256 |
| --- | ---: | --- |
| 主Debug APK | 474,353,265 | `496f5b0ceb274de8bfa2ca14bc7b2dac1b09709b99ae5ff4ed321f53875b5634` |
| AndroidTest APK | 8,307,741 | `85078d6055d334a6b7072959a97c247b95369583addb38123dad54fad93d8c87` |

`snapshot.json`、输入、源SHA、原JUnit XML和lint XML分别保留。与0.5.10已验证测试包比较，
旧52份`assets/media/`路径/字节完全保持，新增22份自有预览fixture；没有缺失旧回归素材。
比较记录为`artifacts/m3-thumbnails/test-assets-first-comparison.json`。

## 首轮真实设备结果

受控API35/x86_64、`emulator-5554`、无快照只读实例；实际console关闭host microphone。
安装前后核对主/test SHA。证据在`artifacts/m3-thumbnails/0511-device-first/`，每轮有独立
input/device/runtime/result记录。任务未并行Gradle/R8、录屏或其他设备自动化；不宣称真机性能。

| 执行 | 终态 | 范围 |
| --- | --- | --- |
| `owned-cancel-first-*` | 1/1，1.518秒 | 原6秒内真实peer关闭，同reader后续帧恢复，独立播放Call不受取消 |
| `owned-close-first-*` | 1/1，1.122秒 | 原5秒内真实peer关闭及fresh reader恢复 |
| `owned-adapter-complete-first-*` | **22/22，25.675秒** | 同次全后端专项，包含上述生命周期；不是拼接 |
| `ui-complete-first-*` | **3/3，32.967秒** | 同次真实Activity、指针流、画面与私密认证专项 |

22项覆盖六格式真实自有Bitmap/PTS/RGB、取消和关闭恢复、共享client主Call隔离、redirect及
AES/fMP4/DASH子请求跨源凭据剥离、当前header缓存分区/签名与语义query、旧header映射碰撞
反例、private磁盘不变，以及停止origin后的progressive/TS/fMP4/AES/DASH离线读取与缺块失败。
这些adapter属于独立opt-in设备类，普通全套应明确排除capability包，不将默认assumption skip
计通过；裸库失败reproducer仍保留。

UI三项以真正DOWN/MOVE进入10秒蓝色bucket，普通模式捕获实际Compose图片像素；UP只产生
一次公开Media3 SEEK discontinuity，CANCEL为零且target/frame和节点均清空。私密模式不截图，
以实际自有Bitmap/UI节点确认蓝色；HTTP A拒绝后刷新B一次并恢复，主player/sourceRevision/
位置、FLAG_SECURE及媒体缓存SHA/mtime均按原值核对。没有注入fake frame或直接调用UI回调。

## 完整界面五项（仅测试补充）

生产源码与主APK不变，只补两个UI设备案例，随后
`0511-build-ui-complete/`的主/测试构建及lint成功，1分30秒，lint0/22。原27项JVM未重跑，
结果明确复用首轮。新test为8,963,975字节，SHA
`527b629734ebfb38cd4c0b84f4c33d5fa06affcd857256bc60dc5db65860074e`；主包SHA仍496f5b。

`0511-device-first/ui-five-complete-*`以此固定新test单次 **5/5通过，29.456秒**。保留原三项，
新增真实playing时持续DOWN3.3秒（没有MOVE刷新计时）仍显示bubble/image、零SEEK且主播放
自然前进；以及private A被真实403拒绝、刷新返回null后，只显示<60dp紧凑时间bubble，
image节点缺失、target非空/frame为空，主播放/FLAG_SECURE/磁盘保持，CANCEL后全部清理。
五项未与首轮22项或旧probe拼成一次27项设备全绿；新旧test分别记录输入身份。

## 尚待收尾

- 本版ARM64/R8构建和打包验证。
- 物理GPU/HDR/厂商codec、其他content provider和复杂公网站点仍按总矩阵保留；受控样本通过
  不等同全设备兼容或物理性能达标。

## 同包常规完整回归

`0511-device-first/routine-complete-*`使用同一主496f5b/test527b62实际执行
**135/135通过，881.462秒**，没有失败或跳过。`routine-complete-tests.json`从原始runner逐项
终态校验135项，含本批完整UI5及原有媒体/离线/权限/文件与目录上传/benchmark/GL。
不是发现测试或把单独轮次拼接；JVM27、adapter22和旧probe的scope仍各自保留。

命令明确排除DownloadRestartTest、OfflineRestartTest、RuntimeSitePermissionIntegrationTest、
AudioCaptureIntegrationTest，以及独立opt-in `app.miru.browser.capability`包。排除不记通过；
不能把默认assumption skip计为执行。任务未并行Gradle/R8、录屏或其他设备自动化；此轮是
既有受控profile，不宣称真正首启、ARM64/R8实际运行或物理性能。
