# MP4 实际元数据读取签名与原始时序

5a3b380之后的阶段实现，版本字段仍0.5.14/code22，未发布新的ARM64包。P11仍pending；
本批没有MP4缓存时间区段发布，也未把raw PTS/DTS直接当应用edit后的呈现区间。

## 实际读取来源

- Mp4CacheRequestContext为每个实际DataSource实例保存有界trace，Loader线程只引用当前
  实例。每次open保留真正的URI/position/原length/key，read按实际返回的字节更新SHA-256。
  sniffer/peek以及small skip所消耗的源字节包含在内；internal seek重新open有独立父key。
- 实际MP4输出的endTracks、seekMap和所有format齐备时冻结签名，不继续hash后续media。
  未选中MP4的extractor以透明ForwardingExtractor在init/read停止收集，底层实现与全部方法
  转发保持；ICY仍拒绝观察。256个open/32MiB实际字节的预算超限只使证明未知，不改传输字节。
- signature仅RAM，Native输出完成顺序不变，没有新增prepare等待/主播放器加载策略修改。
  重新seek0解析会重新建trace，旧proof保持不变；close清当前线程，source/catalog释放拒绝
  迟到发布。尚未把任意跨父边界结构读取伪造成一个key。
- verifiedBytes只从每段原parent key的已提交文件读取，并逐段对比SHA。相同长度的缓存
  篡改也拒绝；不同parent重叠且字节冲突不能组成单一元数据视图。缺口、错key、取消等未知
  情形不形成验证结果。当前结构块需被一段完整覆盖，复杂跨父边界组装仍待补齐。

## 原始时序

Mp4RawSampleTiming独立读取stts/ctts运行长度，用原始decode-order整数tick保存DTS、PTS和
逐sample duration；不展开每个sample对象，不用相邻PTS差推duration。读取tkhd id与mdhd
实际timescale，按固定1.11.1 BoxParser的有符号ctts偏移行为跟随原生时轴。运算检查counts，
微秒换算在乘法溢出时使用精确BigInteger除法，不放宽容差。

该模型是应用edit/PCM rechunk之前的raw时间；它不是已完成的呈现窗口、Native output样本
映射、RAP/GOP/preroll、选中AV或原生I/O闭包。重复/剪裁edit、采样边界与tick量化等必须继续
精确处理，不能把durationUs直接相加造成微秒洞，也不能按字节比例推cache时间。

## 固定输入和实际终态

先前观察/结构候选main b59ecb的常规套件另于mp4-metadata-routine完成143/143、0跳过、
799.742秒；四个独立前置类与capability包仍排除。这不是本批新主包d52939的整组结果。

mp4-proof-build-01：582项无漂移，JVM74/74、lint0/22，独立模块mp4-proof-device-01同次
91/91（15.254秒），另同候选native2/2（7.407秒）。此早期结果不与后续输入拼接。

最终mp4-timing-build-01：584项输入无漂移，构建2分32秒，JVM74/74，lint0错误/22警告。
模块APK内69份素材逐字节保持。

| 固定APK | SHA-256 |
| --- | --- |
| player-test.apk，27,291,070字节 | `61ee1209221d2fef1ed67a9fdd187f3e29b73cc152e8ea1c5df13c6dc116e898` |
| main.apk，474,859,161字节 | `d529390fb985713cda2c50eec9cf30c68d268e27837c9ff562f9615f60f5155f` |
| app-test.apk，9,454,612字节 | `5c74fb44f40d91bd7154171e8f99bb7e2e43b3e354da4fda9a4f031fa2f7bea3` |

- mp4-timing-device-01同次 **94/94，0跳过，20.443秒**，原84项+读取签名7+raw时序3。
  签名测试覆盖init前读取、freeze后停止、实际多父key、同长篡改、重叠冲突、预算不改字节、
  两个Loader线程/reparse以及实际native movie的proof经缓存验证后解析出120样本。
- raw时序对baseline120、VBR头尾各360及raw-edit265样本逐项精确核对PTS/DTS/duration；
  B帧有非单调PTS，邻接差明确不等于duration。另有负ctts与无效counts/version/scale反例。
- mp4-timing-native-01同包独立 **2/2，0跳过，9.430秒**，冷9秒蓝帧无新增上游、冷5秒洞内
  503仍通过。两项不并入94或此前143，原始runner、安装后pull回APK哈希均单独保存。

本候选的完整常规应用、R8/ARM64运行与新打包未记为通过；正式签名/真机及其他原验收项
不因本批基础组件完成而改变状态。原nonidentity edit严格ffprobe比较失败也未修改或抵消。

## 继续完成

验证真实startup后续读路径及元数据来源到Native表/当前选中轨道的绑定；补齐跨父结构块、
post-edit/PCM sample映射、duration端点量化、RAP/NAL和decode/AV/native读依赖，再接入
PlayerCachedTimeline PROGRESSIVE与真实灰条/清理/切源/停供几何。复杂HLS/DASH/多period、
fragmented MP4及其他VBR格式、offline独立显示等仍保持完整目标，不以仅整文件100%替代。
