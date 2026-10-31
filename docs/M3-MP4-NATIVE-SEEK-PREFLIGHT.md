# MP4 原生冷启动缓存读取预验

2026-11-01，API35/x86_64；基线0b2eebd，生产主包保持0.5.14/code22。本批仅新增独立测试，
**没有生产MP4磁盘时间索引/进度条实现，P11仍pending**。原BoxParser/ffprobe的非identity edit
严格比较失败保持在[M3-CACHED-RANGES-PREFLIGHT.md](M3-CACHED-RANGES-PREFLIGHT.md)，本批没有
修改或放宽其断言，也不把identity elst的颜色片段代替复杂edit/AV/GOP验证。

## 原始输入和磁盘设置

owned `media/seek-preview/colors.mp4`，21,552字节，SHA
`8259466bc2ec96cfd7d3866f9ae30d325192eea48e4663beed6b9b839f559357`。
复用独立ISO-BMFF stco/stsc/stsz/stts/stss展开的`media/cached-ranges/colors.samples.json`，
逐样本reference未改。这是120个视频样本、10fps、160×90、无音频/无B帧的12秒素材，
实际sync每1秒，不以相邻PTS或字节比例推时长。

- 4–8秒绿色的40个真实样本占据物理字节 **[8482,15032)**，共6550字节。
- 用实际CacheDataSource/HTTP先完整读取并验证SDK持久总长21552，再删原资源，用实际有界
  Range请求重新缓存前后两段。删除最后span会清metadata，因此恢复的是此前从同一不变且
  固定SHA源实际测得的SDK总长，未把prefix长度当资源长度。
- 最终真实spans为 **[0,8482)**、**[15032,21552)**；前者含ftyp/moov和0–4秒，后者为8–12秒。
  Assert检查绿色不完整，前后完整。seed不创建任何播放器/decoder/SampleQueue。
- 此后HTTP媒体服务固定返回503，才启动新的PlayerActivity与生产PlaybackSourceSession。
  没有旧播放器或已缓冲蓝段。未改生产load策略、Media3 seek实现或缓存键算法。

## 固定构建与真实终态

`artifacts/m3-cached-ranges/mp4-native-build-01/`冻结572项输入，无源码漂移；构建1分4秒，
JVM74/74，lint0错误/22警告。主包与此前常规143项使用的0.5.14主包逐字节一致：

| APK | SHA-256 |
| --- | --- |
| main.apk，474,827,730字节 | `490c9cfe787119bac2d5bcf8a0ff06b861b33c5eb81b24210eccf4885e083315` |
| app-test.apk，9,454,612字节 | `5c74fb44f40d91bd7154171e8f99bb7e2e43b3e354da4fda9a4f031fa2f7bea3` |

`mp4-native-device-01`独立 **2/2，0失败/跳过，8.894秒**。实际安装包pull回后核对SHA，
按所有状态码与最终OK验收；没有只看adb exit0，也未将这两项并入旧测试包的143项。

- **冷启动9秒缓存蓝段**：初次ready时实际position9448ms、duration12000、视频160×90。
  暂停后再seek9，真实VideoFrameMetadata为 **9,000,000us**；SurfaceView PixelCopy实际
  RGB为 **[1,0,255]**，保存的图像时间码 **00:00:09.000**。全过程只有seed的三次HTTP
  请求（0–21551/200、0–8481/206、15032–21551/206），停供后没有新增上游请求。
- **冷启动5秒缺口**：实际error、position5000ms、未呈现目标帧（pts=-1）；native首次
  请求 **10117–15031/503**，重试 **8482–15031/503** 三次。10117是独立表中5秒sync
  样本位置，8482为4秒sync位置，都位于明确缺口。不是通过人为抛错或等待超时制造失败。

实际报告、每次HTTP范围/状态、spans与三份文件SHA保存在`mp4-native-device-01`的
`mp4-native-cold-nine.json`、`mp4-native-cold-green.json`、`files.json`；蓝帧PNG SHA为
`a02afb85b74b283562daf554848e45b960a8aa516d1deffcfaff762a8f6a89e9`。

## 重跑与未完成范围

类 `app.miru.browser.capability.CachedRangeMp4NativeSeekCapabilityTest`，必须显式
`-e miruMp4NativeSeekCapability true`；两个完整方法名和命令保存在每轮input.json。
常规套件继续排除capability包，未启用时的assumption跳过不得算两项通过。

这仅证明该head-moov、单视频、固定12秒素材上的真实冷启动/seek缓存读路径；前缀包含0–4秒，
未证明只靠moov+目标GOP就够，也未证明全部启动调度、未选轨/样本间隙、opaque URL、尾moov、
B帧/复杂edit、AAC等AV交集、fragmented MP4或其他VBR格式。
生产仍需实际extractor/选轨身份观察、cache-only有界metadata读取、逐sample duration/DTS/
preroll/GOP及原生I/O闭包、committed-span监听和真实UI。不能用这两个用例或整文件100%缓存
来替代不连续区段实现与完整P11验收。
