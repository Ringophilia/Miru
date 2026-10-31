# 0.5.12 HLS 选轨、缓存区段发布与真实进度条

2026-10-31，API35/x86_64，开发版本0.5.12/code20。需求保持MIRU-SPEC §8.4/8.5/P11的全部格式和
验收范围。普通流HLS的实际选轨与磁盘层已接入，**完整P11仍pending**；DASH/MP4等索引、未知资源
闭合与非独立segment真实sample证明不因本批通过而省略。

## 实现

- `HlsCacheCatalog`记录官方tracker实际redundant group、当前路径、对应原生wrapper/track index，
  以及isForPlayback=true的实际组选择。主wrapper按固定1.11.1的video/audio过滤顺序，外部音轨
  按原生audio:name分组顺序关联。冗余路径只采用当前URI，不union旧路径。
- `HlsCachedSelection`逐个核对实际选中的AV组、显式override、复用轨的primaryTrackGroupId和
  downstream原始格式；缺少任一必需轨即不发布证明。相同格式且无ID的多个候选用同wrapper的
  实际播放组消歧，不能把所有adaptive alternatives合起来当成当前码率。
- `PlayerCachedTimeline`绑定实际main ExoPlayer/session。播放器API只在application thread调用，
  snapshot资源依赖映射和SimpleCache监听/覆盖计算在IO。period/window/Tracks/参数、catalog及
  resource revision变化撤旧计划；发布同时核对代次、两种元数据revision和cache revision。
- 缓存span移除先同步撤销旧覆盖，再在IO重算；新增span仅触发重算。selection gate从不持有着
  去申请cache monitor，移除回调的cache→gate锁序不成环。父scope取消时先撤UI，不等待可能阻塞
  的cache监听清理；播放器仍由原owner释放。
- Controller在重建、回网页和release时撤旧publisher，输出只含不可变时间区段。仅普通stream HLS
  启用；private不创建，offline继续独立无upstream工厂，不借用stream cache冒充离线证明。
- 实际PlayerScreen按底轨→磁盘白32%→内存buffer→已播放绘制；磁盘内部端点用Butt保证不填平
  时间洞。窗口/来源未ready时不画磁盘层，原P6 Slider输入、拖动所有权和缩略图接口不改。

## 两次真实失败与API根因

首轮选轨设备类6/8通过，两项真实来源在原10秒门内未发布区段。补真实SurfaceView及实际切码率/
音轨场景后，第二轮仍为6/10：四项真实来源均在相同初始门失败，未因补Surface而解决。

只读诊断实际观察到原生main组、downstream和catalog的Format.id均为null，资源请求已进入绑定，
事件period与当前period一致。固定1.11.1的`HlsRedundantGroup.GroupKey`构造器明确清除id和metadata，
`HlsMediaPeriod`又用该规范化format构造各wrapper的轨组。因此最初的ID匹配假设错误。

修复使用同一官方组、真实wrapper/索引顺序和downstream/实际选择关联，不发明ID，不改原超时、
区段或旧语言/旧码率负例。新增null-ID/完全相同format的消歧门。原失败日志、runner和输入保留；
成功不能抵消它们。`selection-upstream-contract.json`固定源码包SHA
`27f36d1714018c7b2a17656f8b5fbf3e93b6bec72aac82990c9ae0fab34a3160`及两个源码成员SHA。

## 固定构建与设备记录

工作树`G:\Projects\Miru`，基线`b40f5d5`加本批源码/测试/版本配置。有效构建均有完整输入前后SHA、
无漂移检查、命令、JUnit/lint XML及固定APK；设备轮保存实际安装base.apk并逐项核SHA。
证据位于`artifacts/m3-cached-ranges/selection-*`；后续本文和矩阵更新不声称属于原冻结APK文档输入。
JDK21/SDK37、PowerShell7、--no-daemon：

```text
.\gradlew.bat :feature:player:testDebugUnitTest :feature:player:assembleDebugAndroidTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --console=plain
```

| 执行 | 真实终态 | 范围 |
| --- | --- | --- |
| `selection-build-01` | 配置失败，27秒，0项新测试执行 | AGP拒绝SourceSet Provider；result中复制的旧JUnit71和旧APK明确不接受为本轮证据 |
| `selection-build-02` | 脚本编译失败，48秒，0项测试执行 | Variant selector接收器作用域错误；后续构建脚本失败即拒绝旧包/旧报告 |
| `selection-build-03` | 成功4分53秒，JVM72/72，lint0/22 | 首次有效选轨/publisher/UI源码；548项输入无漂移 |
| `selection-device-01` | **6过/2失败，0跳过，22.009秒** | 首批选轨8项；真实来源未进入缓存层发布门 |
| `selection-build-04` | 成功1分32秒，JVM72项保持 | 新增真实Surface Activity和实际码率/语言测试，550项输入无漂移；主包与build-03相同 |
| `selection-device-02` | **6过/4失败，0跳过，49.238秒** | 同一错误ID假设，实际日志定位；原断言和超时未放宽 |
| `selection-build-05` | 成功4分11秒，JVM72/72，lint0/22 | 修复无ID原生轨组关联，新增完全相同格式负例 |
| `selection-device-03` | **11/11，0跳过，21.555秒** | 7项选择模型+4项真实source/publisher，包括旧码率/旧语言缓存仍完整时不能补当前洞 |
| `selection-build-06` | 成功3分9秒，JVM72项保持，lint0/22 | 0.5.12/code20和真实UI三项，551项输入无漂移 |
| `selection-ui-01` | **3/3，0跳过，22.068秒** | 实际进度条像素、停供解码/缺口失败、private磁盘/secure窗口 |
| `selection-build-07` | 成功3分22秒，JVM72/72，lint0/22 | 增加来源未ready的绘制门及真实软件解码器重建UI用例，551项输入无漂移 |
| `selection-ui-02` | **4/4，0跳过，31.983秒** | 最终固定应用同包完整UI类，包括重建前立即撤旧层与新来源重新发布 |
| `selection-module-complete` | **41/41，0跳过，15.390秒** | 最终固定独立模块同次Selection11+HLS19+绑定11，未拼接旧轮次 |
| `selection-routine-complete` | **139/139，0跳过，941.713秒** | 同一最终主/应用测试APK的常规完整回归，包括本批全部4项UI；不是把独立轮次拼接 |

完整常规回归已确认runner真实终态，宿主验证器同时核对139个成功status及最终OK。固定主/应用测试包与上表UI
使用同一build-07，明确排除DownloadRestart、OfflineRestart、RuntimeSitePermission、AudioCapture
四类独立前置及capability包；它们不计通过，不把本批模拟器结果代替真机或全量验收。

| build-07固定产物 | 字节数 | SHA-256 |
| --- | --- | --- |
| player测试APK | 25,838,694 | `c39c093559fbbf61a0e16fe4be8578cdf4a943ccfe7b119b0ab7ee2e000f9921` |
| Debug主APK | 474,763,628 | `da73ffcf7213e4405370469ff394d39d832314b1b04e4067274dc2cfe2aca1e1` |
| 应用测试APK | 9,434,617 | `5a9a8cab6d4b0a7a624893d31b3d7047642e00c7e912be02c6ba88e29c0ef38a` |

独立模块目标是app.miru.player.test，UI/常规目标是app.miru.browser。完整参数数组在各轮input.json/
runner.ps1。43项复用素材在最终模块APK内与原文件逐字节一致；新增Surface Activity只在测试APK，
不把测试素材或Activity加入生产包。

## 真实UI、缓存和解码证据

loopback HTTP origin提供120秒受控HLS，由原自有三TS资源跨十个discontinuity重复引用。原资源未改，
独立性证明沿用[素材记录](M3-HLS-CACHED-RANGES-VALIDATION.md)；同一不可变资源可用于多个时间段。
播放器设置真实预缓冲15秒，实际观测bufferedPosition=16,000ms，而磁盘依赖可覆盖0–120秒。

- 移除真实ts-01资源后，UI采用生产选轨/索引/监听输出11段不连续覆盖。84/94秒的缓存像素亮度
  约117.7，90秒洞约51.7，位于内存缓冲以外，均满足原30亮度差门。对应真实Canvas PNG及SHA保留。
- 清掉本夹具三个缓存资源后区段为空，原player对象、暂停状态和位置保持，内存buffer仍大于0；
  没有把bufferedPosition当成磁盘。
- origin停供所有请求（真实HTTP503）后，回拖93秒从磁盘解码蓝色，VideoFrameMetadataListener实际
  presentationTimeUs=93,000,000，SurfaceView PixelCopy RGB约[1,0,255]，原45/channel门通过；蓝色
  分段没有新增upstream请求。再seek89秒的缺口，真实green请求503并报播放失败，没有89秒帧或假覆盖。
  这是受控停供门，不声称真机飞行模式/公网兼容已验。
- private真实HLS已ready且FLAG_SECURE仍开，磁盘层为空，前后stream keys/spans完全相同。
- 软件解码器重建调用内立即清空旧区段、ready=false；新player/来源代次就绪后重新发布完整区段。
- 真正持有SimpleCache monitor的另一线程阻塞清理期间，父scope取消仍撤下已发布覆盖；原player
  未被publisher代为释放。JVM另有同步计算后外部代次变化不能发布，以及移除后IO重算前即撤旧值的门。

`selection-ui-01/02/images.json`记录每轮三份实际PNG及SHA；图片已逐项拉回检查，蓝色图像中的
原素材时间码为09.000，播放器呈现时轴经discontinuity映射为93秒，不混淆两种坐标。

## 尚未完成的完整范围

- 跨会话已有缓存、失败/Range重试后未知长度的可信闭合；不能从EOF后的重造请求或失败前缀猜key。
- 非独立segment的实际sample/GOP证明、LL-HLS parts、动态/复杂多轨与冗余路径的真实矩阵。
- DASH真实Template/Timeline/List/Base+sidx及多period，MP4完整sample/edit/GOP和其他VBR封装。
- offline独立缓存的对应索引与显示策略、完整private生命周期、真实网站/设备/性能矩阵。
- 本批未宣称整个P11/M3完成；ARM64/R8实际运行、正式签名和完整发布门仍需各自证据。
