# MP4 主源观察与只读缓存元数据

2026-11-01，a42b261之后的阶段实现，生产版本字段仍0.5.14/code22，尚未发布新的ARM64包。
**P11仍pending**：本批生产主源已观察实际MP4 extractor，并提供只读缓存结构解析器；尚未
向PlayerCachedTimeline发布MP4时间区段，没有把公共sample表直接当完整decode/I/O闭包。

## 生产路径

- normal/main且实际缓存可用、未强制以无缓存adaptive上游回退的progressive源，使用固定
  1.11.1 DefaultExtractorsFactory及观察包装。实际选中的Mp4Extractor仍执行全部sniff/read/
  seek/release，其他extractor不替换。工厂完整转发subtitle parser、text transcoding、within-GOP
  codecs和HAGC设置及两个create入口，保留返回新工厂的语义。
- 记录实际输出id/type/顺序、Format对象、duration、endTracks与seekMap。重复track(id)保持
  同一TrackOutput，所有format/sample/duration回调照常转发。snapshot保持native元数据引用，
  不打印/持久化URI或认证字段；过多/矛盾输出撤销观察，close/release拒绝迟到发布。
- loader线程上的Mp4CacheRequestContext保留真正外层DataSpec的URI/position/原length/key，
  不把EOF长度重新放进DataSpec；线程间隔离，open失败和close清除当前引用。当前request只
  表示当前打开，尚不是完整ftyp/moov读取来源集合，不据它猜测多个父key的元数据来源。
- preview/private/offline、非MP4实际extractor和ICY地址转换不发布此MP4 catalog。默认flags
  由受控工厂建立，未知自定义工厂profile不作为解析证明。该观察不另取清单/媒体、不改加载策略。

## 只读结构解析

`CacheOnlyMp4Bytes`仅按传入的实际原parent key读取已提交缓存文件，不创建DataSource/
upstream/sink/hole锁，不扫描其他key补洞，也不写缓存。检查坐标、文件长度、相邻span和一字节
洞，在cache monitor下读取，IO线程及分块取消检查保证不在UI读磁盘。

`CachedMp4MetadataReader`有界组装ftyp/moov的公共BoxParser结构，应用默认edit与QuickTime/
UDTA gapless/HAGC策略。支持extended容器和co64；固定native不支持的extended叶不假装支持。
32MiB复制预算、32层/32768节点、展开前合计200万sample及数组长度检查拒绝异常元数据，
缺失/错位/不完整结构不返回部分table。large PCM/rechunk与内存压力等完整边界仍待扩展验证。

返回的structuralBytes只是**结构字节**，不是完整冷启动I/O或decoder init闭包。稀疏尾moov
能被解析不意味着native会跳过缺失mdat；实际metadata读来源/签名、skip和peek路径、原生启动
读取依赖、逐sample duration/DTS/edit/GOP/preroll与选中AV闭包仍须完成后才能发布时间条。

## 原失败与证据边界

- mp4-observer-build-01实际因测试旧名称DummyTrackOutput编译失败；改为固定SDK的
  DiscardingTrackOutput。失败构建未接受APK/旧报告。
- mp4-observer-device-01在安装前ADB目标不在线而中止，**0项测试**；原安装日志及
  host-preflight-failure.json保留。恢复WfmAdApi35/5554后，用固定APK重跑device-02。
- mp4-metadata-build-01因测试LongArray.zipWithNext编译失败；仅改为asList，不改断言。
- mp4-metadata-device-01为**81过/3失败，27.655秒**。三项首PTS期望66666us、实际0：
  VBR参考显式ignore_editlist=1，而生产默认应用独立导出的elst media_time1024/timescale15360。
  保持生产默认，修新测试oracle：从参考的截断微秒唯一恢复整数tick，逐条反算必须精确复现
  原参考微秒，再按独立elst做整数换算。字节/size/flags/payload SHA、样本数和PTS均无容差。
  原参考未改，历史nonidentity edit严格ffprobe失败仍保留，不用本批抵消。

## 最终冻结输入与真实终态

mp4-metadata-build-03：**579项输入无漂移**，构建1分43秒，JVM74/74、lint0错误/22警告。
最终模块APK内69项素材逐字节一致（原59项加10项固定MP4参考/媒体），只在androidTest。

| 固定APK | SHA-256 |
| --- | --- |
| player-test.apk，27,032,883字节 | `1896b838fd96faf8d6e84ef9a88d9dc63ef56501569916ff9c4302f5028a3483` |
| main.apk，474,850,454字节 | `b59ecb61a76291483d98fc33a414704f0e022891ac808ecb50f59ecf78f9492d` |
| app-test.apk，9,454,612字节 | `5c74fb44f40d91bd7154171e8f99bb7e2e43b3e354da4fda9a4f031fa2f7bea3` |

- mp4-observer-device-02独立**8/8，0跳过，3.061秒**，使用observer-build-02；含实际main
  MP4的Format/metadata对象身份、seekMap/文件长/原request，实际preview/private/offline/
  WAV隔离，以及完整工厂转发、重复输出身份、超限和迟到release。此早期输入不拼入最终轮次。
- mp4-metadata-device-02最终同次**84/84，0跳过，27.859秒**：原70项+观察8+解析6。包含
  baseline120样本与强VBR/B帧头尾360样本的全部offset/size/PTS/flags/payload SHA，稀疏元数据
  不读media、不产生key/字节增长，错key/一字节洞、取消、异常声明，以及尾moov扩为16字节
  容器头并将stco无损改co64后全部样本仍相同。extended/co64仅证明结构解析，不作解码声明。
- mp4-observed-native-01另**2/2，0跳过，15.044秒**，使用metadata-build-02；其main和app-test
  SHA与最终build-03完全相同，后者只修模块测试oracle。真实冷9秒PTS/RGB、停源零新增上游，
  冷5秒实际缺口503仍通过。原报告/截图/文件SHA独立保存，不并成86项或冒充完整应用套件。

相应runner都安装固定副本、pull实际base.apk核对SHA，并检查每个状态码和最终OK。
新主包b59ecb与此前143项主490c9c不同；尚无此候选的143项、R8/ARM64运行或新打包声明。

## 下一步

先建立缓存结构字节与实际native读取的来源/签名校验（含多父key、peek/skip与重试），再完成
sample duration/DTS、编辑窗口、RAP/decode与原生I/O闭包、选中AV和UI。不能按相邻PTS或字节
比例推时间，也不能仅整文件100%或采样缩略图替代不连续磁盘区段。复杂PCM/大表、B帧/edit
实际解码、尾moov启动、opaque URL、fragmented及其他VBR、缓存错误/清理/鉴权等完整矩阵
仍在P11原范围；M4/真机/正式签名/iOS等原目标不变。
