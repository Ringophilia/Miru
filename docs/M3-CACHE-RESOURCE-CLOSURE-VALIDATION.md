# 0.5.14 跨会话普通流缓存资源闭合

P11仍pending。此批补普通HLS/当前period DASH的新会话未访问资源，不把旧0.5.13或单独专项
扩大为整个规格、真机或R8运行通过。源码基线472eaf4，实际证据在`artifacts/m3-cached-ranges/closure-*`。

## 实现与信任边界

- `CachedResourceResolver`使用main session不可变headers分区和原始URI/position/requestedLength，
  已观察请求保留实际key；未访问请求用该session同一key factory构造原坐标请求，不把EOF长度
  塞回DataSpec。已声明正长度始终要求全部字节；无界资源依当前SDK
  ContentMetadata总长闭合，旧观察长度不能覆盖缺失或改变的SDK终点；不能用最大span.end代替EOF。
- 固定Media3 1.11.1 CacheDataSource在无界实际source返回长度时、或真实上游EOF时保存资源终点；
  这些metadata只建立字节依赖，不能证明已完整写盘。最终仍与同一缓存快照内的committed spans
  逐字节验证。残缺前缀、一个字节洞和缺init/key都不补时间。
- `CachedResourceBindings`保留有界RAM失败记录，区分未访问、打开中、未知长度与已失败；失败、
  不足声明长度和提前EOF终结本次观察，迟到EOF不能借持久metadata复活，重新open建立新身份。
- tracker在IO上从一致metadata/spans重建依赖，先登记新发现的资源key再重取快照。即使初始没有
  任何可用interval，也监听已查询资源；init/key存在后可以逐层发现后续依赖。替换/删除/取消
  撤销旧发布，并检查source/catalog/cache代次。HLS大清单扫描在依赖解析和前驱链中检查取消。
- Cache.Listener没有metadata变化事件。主播放与preview的实际CacheDataSource在open/EOF/close
  发出按Cache身份共享的RAM revision（WeakHashMap），无需新fetch、缓存写入或持久索引。IO
  worker据此重算，覆盖“缓存洞填完后读取已有尾部，最后EOF仅更新长度、没有新span”的情况。
  preview自己的headers决定其cache partition，不能替换main身份；private/offline不创建此stream路径。
- DASH以实际holder及DashUtil构造未访问单段的原请求。仅允许生产key忽略flags、maxSegments=1、
  无动态CMCD的已知配置；其他配置继续用实际发出的父DataSpec。merged children保留父key，
  各自检查范围，不要求整父范围都缓存。当前period/SAP/DRM/选中AV及PTO边界与上一批一致。
  同时修正segment枚举距离的减法溢出，异常边界在访问segment之前拒绝。

## 原始失败与修正

`closure-red-build-01`只增强测试，主包仍为0.5.13原包d25b2e、模块包756259。新HLS用例在
117秒重建source且仅允许清单网络请求；DASH两项要求新decoder后仍显示完整原holes，包含
本次未访问的红段。原PTS/45每通道RGB/8秒蓝帧等待/无新增媒体请求断言保持。
`closure-red-ui-01`为 **5过/3失败**，三项均在完整holes的10秒门失败；此前真实蓝帧及无新增
媒体请求已通过。没有把仅9秒附近出现范围当作完整缓存显示。

`closure-build-01`首次实现569项冻结输入，JVM74/74、lint0/22；`closure-ui-01`同次8/8，
35.879秒，原三项范围失败均通过。但`closure-module-01`为 **66过/1失败，25.495秒**：
删除最后一个span时SDK也删除长度，测试直接重填字节后误以为长度仍存在。该失败保留。
修测试改为实际CacheDataSource重试并发现EOF，另补真实metadata-only事件通知，不伪造长度
或改为从span.end猜总长。最终测试保留删除/填洞/真实EOF后恢复的完整路径。

## 候选轮次与最终固定输入

`closure-build-02`为0.5.14/code22：570项源输入无漂移，构建3分23秒，JVM74/74、lint0/22；
模块同次69/69（21.140秒）、UI同包8/8（35.251秒）。该轮不与后续输入拼接成一次通过。
复查真实清缓存后再填充流程，补充原native EOF256而新SDK长度512的反例：要求512全部字节，
不把已填回的256字节前缀当作完整资源；SDK长度缺失时完整外观span仍需要原生EOF探测。
随后实际512字节全部缓存可恢复覆盖，不永久拒绝已完整的新资源。

`closure-build-03`仍为0.5.14/code22，570项输入前后无漂移，构建2分35秒，JVM74/74，
lint0错误/22警告。最终模块APK内59项素材逐字节相等。

| 固定APK | SHA-256 |
| --- | --- |
| player-test.apk，26,633,330字节 | `c8994d552d327afe9017e0b6a073f25028b35f507bafbdd8fcc820e326c29a20` |
| main.apk，474,827,730字节 | `490c9cfe787119bac2d5bcf8a0ff06b861b33c5eb81b24210eccf4885e083315` |
| app-test.apk，9,454,612字节 | `922e7bf75c771c2d06e501226ad40233092ef0a795b541c6788d8440c1a53152` |

`closure-module-03`同次 **70/70，0跳过，23.273秒**；`closure-ui-03`同包 **8/8，0跳过，36.863秒**。
HLS新source仅访问117秒蓝段，仍恢复11段完整holes；DASH两项新decoder后保留完整红/蓝holes。
各自保持原PTS、RGB、清init/清缓存、私密及无新增媒体请求门。普通流metadata增加不改变内存buffer或seek所有权。
`closure-routine-complete`最终同包常规 **143/143，0跳过，954.367秒**。明确排除
DownloadRestartTest、OfflineRestartTest、RuntimeSitePermissionIntegrationTest、
AudioCaptureIntegrationTest四个独立前置类与capability包，排除项不计通过。
本批全部8项缓存UI、缩略图、文件/目录交互、离线、benchmark与真实shader均在该整组内通过。
整组结束后取回三张实际PNG，SHA与专项UI蓝帧一致；原PTS/RGB、完整holes及无新增媒体请求
均由该轮实际断言通过。此时logcat环形缓冲已无早期缓存标签，首次可选PID提取实际报
StopIteration，未更新文档即退出；限制记录在early-logcat-capture.json，未把专项日志拼作本轮
进程追踪。源码相对R8冻结输入只改验收Markdown，见`closure-release-build/source-review-final.json`。
模块由DASH19、HLS索引20、HLS选轨11、资源绑定11及新增资源闭合9项组成；新增闭合使用
实际SimpleCache/CacheDataSource验证原key、未知EOF/failed-prefix、已知长度部分写入、
merged一字节洞、从空索引唤醒、四资源依赖发现、headers/private/offline/close、旧计算替换
和清空后新长度。它们是磁盘/metadata证据，不另作视频解码声明。

## cf1c52a 干净源的 R8 与 ARM64 开发包

提交 `cf1c52aa6b9f50dc521ed705c5be33d815e503cc` 后，从干净源执行
`.\gradlew.bat --no-daemon :app:assembleRelease :app:lintRelease`，成功4分2秒，lint0错误/
22警告。571项源输入前后无漂移，证据在`closure-release-build/`。相对最终Debug build-03
仅三份验收Markdown变化，`source-review-debug.json`确认没有其他代码变更。

- AGP原包111,283,379字节，SHA
  `728d9c6bc6298e4a89cf88d700d2ed38c5322d5fcca342a39f2a6cae44a2aea4`。
- 最终 `artifacts/releases/Miru-0.5.14-arm64.apk` 为 **108,135,802字节**，严格小于
  110,000,000；SHA `57c091e95da603073555728da03c3a5e90fb1268954b0d1d6c12a61357426d89`。
  与旧包体积相同不代表载荷相同；八步流水线独立验证了全部1538项当前载荷。
- 只复用旧0.5.13包中内容相同条目的压缩字节。当前1538项payload与R8原包逐项相等，SDK
  16KB zipalign、签名及同证书检查通过。证书仍为Android Debug：
  `5aa1a00e59b8638298dbddcc5a61053ca245249136edb67199e0c9a3e4cb1f84`。
- 17个库全为ELF64/AArch64，无其他ABI。Gecko的4077项原始载荷、两项精确修改和五项
  固定新增资源验证通过，omni SHA为
  `f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`。
- 流水线在同名`.verification-20261031-210253-00dc80f7/pipeline.json`，原生/Gecko报告在
  `closure-release-build/native-verification.json`与`omni-verification.json`。

这些只证明构建、载荷完整性、证书/对齐和体积；**正式签名、ARM64/R8实际运行仍待**。

## 仍需完成

DASH整窗口跨period历史/未来覆盖、动态/live/LL与多BaseURL/CMCD等全部配置；非独立段
的sample/preroll依赖、复杂HLS、MP4/其他VBR、offline独立显示、完整Range重试/鉴权/清理/
跨会话与真实站点矩阵仍保持原目标。metadata自身不是codec有效性证明，损坏媒体与物理磁盘/
缓存IO错误的完整矩阵也未据局部通过完成。正式签名、R8/ARM64实际运行、真机性能、M4/M5、
iOS等原验收缺口均保留。
