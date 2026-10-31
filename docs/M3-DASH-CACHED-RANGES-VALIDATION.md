# 0.5.13 DASH 磁盘区段与解码器重建布局

本批继续实现 P11；P11 和整份规格仍未验收通过。证据位于
`artifacts/m3-cached-ranges/dash-*`。只将逐轮实际运行结果计入对应输入。

## 实现范围

- 普通 stream cache 的实际 DASH 主源接入观察型 `DashChunkSource.Factory`，转发
  固定 Media3 1.11.1 的字幕解析、sample dependency、TransferListener 和输出格式设置。
  preview/private/offline 不接入 stream catalog。
- 从原生 holder 及已完成的初始化/sidx 加载读取实际 index、BaseURL、segmentNumShift；
  时间已含原生 PTO 调整，映射到当前 period/window 时只做一次投影，并裁剪 period 两端。
- 保存实际发出的原始 DataSpec key/position/length，merged load 的每个 child 使用自身
  精确范围；未知后缀只接受原父请求的可信长度闭合，不用总字节比例推时间。
- 使用原生 TrackGroup 对象、manifest 对象、period、选轨 override、downstream Format
  和当前 representation，旧码率或旧 period 的缓存不能填补新选择的洞。
- 官方 MPD parser 保持原样；有界旁路记录 startWithSAP 继承与 override。仅有 SAP1/2
  证明的普通段进入映射；缺失/无效/SAP0/3/DRM 的时间依赖仍未知。超过 8MB 记录上限
  继续官方解析和播放，不以无限制清单副本换取标记。
- source-owned publisher 与既有 committed-span/AV交集/UI 共用，切源、释放、删除
  init/index、磁盘移除都会撤销旧证明。这里只发布当前 period 的当前选中 representation。

## 夹具与失败保留

新增 owned colors.mp4 派生的 global-sidx fMP4，SegmentBase、SegmentList 与 2 秒 PTO
清单。固定工具、输入输出 SHA、atom/range、原始 SAP 位和独立 fragment 解码命令见
`app/src/androidTest/assets/media/cached-ranges/dash/manifest.json` 与 README。
原 FFmpeg sidx 的 starts_with_SAP=1、SAP_type=0 未改写；派生 MPD 的 startWithSAP=1
另由三个独立 init+fragment 首包关键标记及 RGB 解码支持。最初错误要求 sidx 类型 1/2
的生成失败保留于 `dash-fixture-generation/failed-01/`。

`dash-device-02` 的多 period 用例原为 15过/1失败：先直接 seek21秒时，仅20–24秒
实际加载，测试却要求12–24秒。日志证实 publisher 只报20–24秒。修测试先真实加载
period1起点，再检查12–24秒及后续seek21；没有放宽缓存覆盖断言。

`dash-ui-complete` 与 `dash-ui-diagnostic` 均为 **6过/1失败**：软件解码器重建后
9秒metadata已回调，Surface合法，缓存区间覆盖目标，但 PixelCopy 为黑色。首轮
`dash-ui-01` 的3/3和后来的单独3/3不抵消这些失败。

加入 Surface 事件记录后，`dash-ui-geometry-red` 为 **1过/2失败**。记录显示16:9
输出在source2短暂变为2340×1080，然后回到1920×1080。该诊断最初要求所有物理尺寸
完全不变，同时也拦截了横屏/insets期间2204×1240到1920×1080的合法等比例变化。
最终回归改为校验16:9比例，允许一像素舍入和合法viewport变化；原9秒PTS、8秒蓝帧
等待与45/channel像素阈值不变。旧诊断结果完整保留，不当作最终同断言的红绿对照。

第一次仅保留View内部几何的候选build-09仍在相同比例门失败（dash-ui-fixed为6过/1失败，68.040秒）。controller已经报告过160×90，但Compose尚未交付这次状态时便发生重建，View没有收到可以保留的旧尺寸。后续候选由controller保存每次实际VideoSize并向View提供独立的layout-only几何，覆盖这种快速切换。

RoutedVideoView 在同内容的新 decoder 尺寸暂未知时保留上次已知的布局几何，避免
先铺满viewport再恢复比例。新输入的未知尺寸、HDR、protected metadata 仍驱动路由
与超分安全决策；没有用旧格式启用渲染，也没有通过恢复播放、额外seek或延长等待修饰测试。

## 已有轮次

| 轮次 | 结果 | 范围 |
| --- | --- | --- |
| dash-device-01 | 12/12 | 初始精确range/parser/index/原生来源 |
| dash-device-02 | 15过/1失败 | 含上述多period测试设置错误 |
| dash-device-03 | 16/16 | 修正实际加载设置后 |
| dash-device-04 | 18/18，20.300秒 | 补原生AV交集与representation override |
| dash-module-complete | 59/59，33.595秒 | 同次DASH18+既有HLS/绑定41 |
| dash-ui-01 | 3/3，22.112秒 | 初次真实sidx/PTO/私密UI |
| dash-ui-complete | 6过/1失败，59.965秒 | DASH3+HLS4，PTO蓝帧失败 |
| dash-ui-diagnostic | 6过/1失败，90.592秒 | base蓝帧失败，PTO通过 |
| dash-ui-surface-trace | 3/3，20.064秒 | 原生产包；不能抵消前述偶发黑屏 |
| dash-ui-geometry-red | 1过/2失败，32.391秒 | 捕获比例重置，也含过严物理尺寸门 |
| dash-ui-fixed | 6过/1失败，68.040秒 | 仅View内保留几何仍漏过快速decoder替换；相同比例断言失败 |

## 当前候选的固定输入

工作树 `G:\Projects\Miru`，基线18fa2b8之后的0.5.13/code21候选。`dash-build-10`以566项
输入冻结构建，前后无漂移：JVM72/72，0失败/错误/跳过；lint0错误/22警告，构建3分55秒。
命令为 `:feature:player:testDebugUnitTest :feature:player:assembleDebugAndroidTest
:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --console=plain`。

| 固定APK | SHA-256 |
| --- | --- |
| player-test.apk，26,624,613字节 | `75625910df0164b1246e95af820146df38712f5106cb94610f1be8d5dec668dd` |
| main.apk，474,803,665字节 | `d25b2e0e3755af2e4fbddd3fc37845e021b6254ea703d833babe1505f901e675` |
| app-test.apk，9,452,508字节 | `028b8762c9adb9f75d0f1e040a4fa846260f54956a91c26bd3d485c72f35f234` |

`test-assets.json`逐字节验证最终模块APK内59项测试素材。设备runner安装固定副本后pull回
实际base.apk核对SHA；按全部状态码和最终OK计数，不只看adb退出码。

`dash-ui-controller-layout`同次 **7/7，0跳过，33.159秒**，包含DASH3与既有HLS4。
base/PTO两项原生sidx分别得0–4/8–12秒、0–2/6–10秒，删除中间span不借完整视频填洞；
停止媒体供应（MPD保持可达）后，新软件decoder在9秒metadata并以PixelCopy显示
RGB约[1,0,255]，媒体15384偏移没有新增上游请求。移除init/index撤销区段；private保持
FLAG_SECURE、没有磁盘层且stream spans不变。相同比例断言未再看到2340×1080跳变，
仍允许合法窗口缩放。截图SHA与早期蓝帧一致，实际160×90画面时间码00:00:09。

`dash-module-final`误填两项旧类名，实际29项通过、两项JUnit initializationError（22.753秒），不能算59项通过；日志与原命令保留。已按原59项类名重跑；`dash-module-final-02`同次**59/59，0跳过，50.155秒**，包括DASH18、HLS选轨11、HLS索引19、资源绑定11。

`dash-routine-complete`最终同包常规 **142/142，0跳过，1465.085秒**。明确排除
DownloadRestartTest、OfflineRestartTest、RuntimeSitePermissionIntegrationTest、
AudioCaptureIntegrationTest四个独立前置类与capability包，排除项不计通过。原DASH3项、HLS4项、
缩略图5项、文件上传/目录、真实超分与benchmark均在这一整组内通过；旧失败与早期单独轮次仍保留。

本轮DASH场景的当前进程日志和两张9秒蓝帧另保存于dash-routine-complete的
`dash-ui-current-process.log/json`和PNG，RGB仍约[1,0,255]，没有比例失真。最终源码相对R8冻结
输入只改验收Markdown，见`dash-release-build/source-review-final.json`。

## 83f2da9 干净源的 R8 与 ARM64 开发包

代码和上述专项记录提交为 `83f2da9354eeb5f5f7703a91b84b7da257270750` 后，从干净源执行
`.\gradlew.bat --no-daemon :app:assembleRelease :app:lintRelease`，成功7分48秒，lint0错误/
22警告。`dash-release-build/source-before.json`与`source-after.json`的567项输入无变化。
相对Debug build-10仅三份Markdown文档差异，详见`source-review-debug.json`，没有把后续代码
拼进原APK的通过声明。

- 原AGP ARM64包111,282,415字节，SHA
  `da13c64f1a4697463155c509749c69538593ff879c11525e497f2a527bca18da`。
- 最终 `artifacts/releases/Miru-0.5.13-arm64.apk` 为 **108,135,802字节**，严格小于
  110,000,000；SHA `5895832134098c75a64a7dad316b890f9bbe5fda763bfa65267104d10e424cd3`。
- 仅复用旧0.5.12包中与当前R8包相同payload的压缩字节。独立核验全部1538项payload与
  当前R8输入逐项相等，16KB zipalign、签名验证及同证书校验通过。证书仍为Android Debug：
  `5aa1a00e59b8638298dbddcc5a61053ca245249136edb67199e0c9a3e4cb1f84`。
- 17个原生库全为ELF64/AArch64，没有其他ABI。Gecko验证4077个未改原始载荷、两项精确
  patch和五项固定新增资源；omni SHA仍为
  `f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`。
- 完整八步打包证据在同名`.verification-20261031-195149-1a20b89f/pipeline.json`，原生/
  Gecko结果在`dash-release-build/native-verification.json`和`omni-verification.json`。

这是构建、完整性和体积证据；**ARM64/R8实际运行与正式签名仍未通过**。Debug/API35专项
或常规套件都不能代替这些运行门。

## 尚未证明

DASH整窗口跨period历史/未来覆盖、未观察/跨会话已缓存资源的索引闭合、非独立段的
前驱/解码依赖、动态/live/LL、BaseURL/鉴权/签名/清理与完整生命周期矩阵，以及 MP4/
其他VBR、复杂HLS、独立offline显示策略均仍待。真实ARM64/R8运行、正式签名、物理设备、
M4及其他原规格缺口不因本批受控API35测试改变状态。
