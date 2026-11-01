# 0.5.15 单视频 AVC 磁盘时间区段

9267c73之后的纵向阶段。P11整体仍pending；不是全MP4、AV或其他封装的完成声明。
本批首次把已验证的MP4结构、实际native选轨与字节依赖接入PlayerCachedTimeline和PlayerScreen。

## 已接入的生产合同

- MP4分支与HLS/DASH共用原committed-span监听/IO worker/源代次失效路径，metadata与body的
  key都属于当前main headers，不从preview更新身份。private/offline不接stream layer。
- 选择要求真实输出ordinal对应native Group id，Format metadata对象来自同一extractor owner，
  并归一化ProgressiveMediaPeriod已知克隆字段；同值但不同元数据对象不借轨，禁用/override、
  crypto和seekable状态均参与门控。seekable变化也使Input代次变化，不能残留旧窗口区段。
- 先验证实际metadata读签名，读取相同缓存结构，然后单视频/AVC、非加密、正常NAL长度、
  无PCM重打包/单一保留edit等条件下，将raw tick映射到native PTS；全部起点须精确吻合。
  每sample终点用tick端点分别换算，不能用相邻PTS或start+截断duration造成微秒漂移。
- stss仅作为候选，实际缓存NAL须确认IDR且无非IDR VCL。依赖从native seek点按decode顺序
  到目标样本，包含skipFully会消耗的小间隙；固定>=256KiB或后退则按native语义重新打开、
  使用原URI/新position/LENGTH_UNSET计算未来main key，不伪造父请求或按字节比例画时间。
- metadata、GOP/媒体等全部required bytes交给同一coherent spans计算；一字节洞、缺init、
  实际IDR不成立就撤销对应证明，旧native内存buffer不算磁盘。IO/取消/选择代次不符不发布。

当前adapter明确拒绝多exposed轨、非AVC、复杂剪裁/rechunk等未验证配置，继续实现它们是
原任务，不把拒绝范围当最终方案。特别是AAC/多语言/字幕混合轨道的原生读取调度和preroll、
多edit输出映射、HEVC/其他codec/fragmented/VBR封装仍待，不能因这一纵向通过就验收P11。

## 实际受控用例与原失败

12秒owned baseline按独立120样本表缓存0–4/8–12秒，留物理[8482,15032)洞；seed不创建
播放器，随后停供再建PlayerActivity。9秒实际PTS/RGB、完整两个区间以及删除metadata后空层
通过，清理不重建播放器、不恢复播放、不清native buffer。5秒实际503且不标可用。

单独mp4-ui-device-01为2/2（9.233秒）。模块mp4-ui-module-01为97/97（20.182秒），新增3项
覆盖同GOP一字节洞影响后续但不影响下一IDR、仅stss但实际非IDR、以及
同值不同owner Format metadata不能绑定；原94项保持。

为看见memory之外的磁盘层，新generator无损循环原owned视频10次，固定工具SHA并独立导出
1200个整数packet参考；所有payload与原120样本逐个循环相等。long-colors.mp4为207888字节，
SHA46c2e946b49cabe0f34146fe0a331048247b4c8f62c5c70dd6497e4d894eb6e1，参考SHA
5ba09bca3264b1655337bdf0fb6169dc8e1376c88ac55d86d443ba059a5e567d。仅androidTest资产，
不是主包新增视频，也未引入FFmpeg运行依赖。

长片种子保留全部红/蓝、移除10段绿色，冷9秒允许future绿色16秒等真实503预取，但cached
目标/metadata不得新增请求。disk应恢复11段完整holes。native memory约15.9秒，84/94秒
亮度117.7、90秒缺口51.7，原>=30门通过；图像取自真实Canvas，原PTS/蓝帧仍独立检查。

mp4-ui-device-02为2过/1失败（19.824秒）：long在8秒蓝帧门失败，已收到9秒metadata，未
执行后续灰条像素断言。该轮未保存RGB/route，不能追认根因或称生产已修。仅加诊断的新test
mp4-ui-device-03原生产主包不变，3/3（13.711秒），long蓝帧和灰条都过。
之后在相同PTS/RGB/8秒门前要求window焦点/landscape、Surface有效且layout/frame尺寸一致，
避免把尚未完成的视觉布局当稳定绘制状态；不是放宽像素或延时。先前失败仍保留，快速暂停/
旋转完整稳定性不因该前提通过而推定解决。

阶段build-06冻592项/JVM74/lint0/22，module97/97（23.075秒），同包合并HLS5+DASH3+
MP4三项共11/11（42.302秒），素材71项逐字节一致。随后仅把seekable纳入Input相等性，
build-07及相应最终回归见下，不把前一输入结果拼为新输入通过。

## 最终固定输入

`artifacts/m3-cached-ranges/mp4-ui-build-07/`冻结592项输入，无源码漂移，构建成功3分48秒。
JVM **74/74**，lint **0错误/27警告**。相对build-06增加的五项均为随时间变化的依赖版本提示，
原22项仍保留；本批没有升级依赖或把警告记作通过项。模块APK内71项owned素材逐字节等于
源文件，报告为`assets-byte-verification.json`。这71项不包含被合并进模块APK的shader等其他资产。

| 固定APK | 字节数 | SHA-256 |
| --- | --- | --- |
| main.apk | 474,898,572 | `b7561783c5a609121f907085ad37798d8ae4798630e35ff56ee9f2faa68bb2c8` |
| app-test.apk | 9,477,875 | `7df9e7d9cb539c18a4c72a81b5ca77743034095ef26adca05d161ef4dc1df0b9` |
| player-test.apk | 27,299,618 | `8af91a3ba742a32a88c70c8a5332921d4457f69dd80956aa4201f07ec0f6a545` |

- `mp4-ui-module-window-final`同次 **97/97**，0跳过；JUnit **47.120秒**，runner全程51.584秒。
  该轮包含之前94项和本批AVC三项，完整类选择与输入SHA在`input.json`。
- `mp4-ui-window-final`同包 **11/11**，0跳过；JUnit **69.909秒**，runner全程76.270秒。
  HLS5+DASH3+MP4三项均在同一次完整选择中通过，保持PTS、RGB、完整holes和像素门。
- 最终UI PNG/JSON取回于`mp4-ui-combined-final/`。120秒长片磁盘11段、memory15900ms；
  84/90/94秒实际亮度117.666664/51.666668/117.666664，9秒PTS9000000、RGB[1,0,255]。
  metadata移除后disk空、同一暂停player及native buffer保留。已查看真实Canvas条与蓝帧图。
- `mp4-ui-routine-final`固定同包完整常规 **145通过/146，1失败**；JUnit **1133.900秒**，
  runner全程1138.877秒。唯一失败是原超分benchmark QUALITY档在45秒期限内未取得60帧，
  FAST/BALANCED实际同步计时为389.2/532.0ms；当前日志不足以唯一确定QUALITY失败根因。
  MP4三项和其余143项在该整组内通过，不用独立97/11抵消整组失败。四个独立前置类与
  capability包仍排除，不计通过。本轮另取回七份实际MP4 JSON/PNG，长片PTS/RGB/holes/
  memory与亮度门均通过，见`mp4-proof-files.json`。正在单独捕获benchmark阶段诊断，不预记修复。

随后用户要求清理磁盘，提交109c0b9只改清理脚本、AGENTS与说明。清理完成当时与该构建的编译输入
逐项SHA一致，`source-review-current.json`仅列这八项非编译差异。按[保留规则](VALIDATION-ARTIFACTS.md)
保留最近两份构建；历史APK被清理时，原日志、输入SHA和失败仍保留，不能声称旧包仍在磁盘上。

## 整组失败的阶段诊断

随后从同一logcat缓冲取回原整组benchmark事件，并在新的独立进程完整捕获两项benchmark专项。
输入仍为build-07固定同包，未更换renderer、shader、帧数、输出或计时门。
`mp4-benchmark-diagnosis-01/events.json`保留两次run的阶段、单调时间和frame状态：

- 整组run20931494658835在QUALITY的COLLECTING阶段于45206ms超时，只有37帧；平均同步
  render时间871.2109ms。FAST/BALANCED释放回调分别449/505ms，均已进入下一档；这次不是释放卡死。
- 隔离run21383569765212在QUALITY的44722ms已取得60帧、平均586.5273ms；随后进入
  AWAITING_RELEASE，45177ms时仍按同一个45秒期限报“有效帧不足”，实际释放回调在45436ms到达，
  只耗时689ms。已完成采样仍因共用期限失败，是独立的阶段计时问题；隔离专项为1过/2，并未通过。

后续应修复已完成采样与有界释放各自计时，并保留整组37帧采样不足这一不同失败。
不能用隔离60帧或将来的释放修复抵消整组失败，也不据模拟器同步计时宣称物理GPU达标。

## 仍需完成

本版完整常规146为145过/1失败，R8/ARM64构建/运行和正式签名尚未通过。单轨AVC不是全P11：多轨AV/
音轨切换/字幕、复杂edit/PCM、其他codec/容器、全部startup/seek/read调度、多父边界、签名/
鉴权/清理/缓存错误与真机矩阵继续按完整文档推进。旧nonidentity edit严格ffprobe失败保持。
