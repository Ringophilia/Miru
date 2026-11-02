# MP4 多轨原生读取调度对照

2026-11-02，基线29344ac。此批提供后续多轨AV闭包所需的读取模型与原生对照，
**尚未向生产多轨MP4发布磁盘区段**；P11、AAC/preroll、解码和UI要求不因模型通过而验收。

## 固定原生算法与许可

`Mp4ReadSchedule`依据固定Media3 1.11.1 Mp4Extractor的累计样本大小、下一轨选择和readSample
调度。输入是按实际exposed顺序排列的post-edit原生表、调用者提供的真实seek起始样本与位置；
不是从总文件字节比例推时间。它保留：

- 各轨decode-order头部按PTS合并，PTS相同由后轨先进入累计字节序列；不把B帧数组排序。
- 物理近处优先、严格10MiB读超前阈值；ITUT-T35时间不晚于video时优先。
- 小间隙skipFully消费字节；后退或间隙≥256KiB时在真实sample offset重新打开原parent。
  输出保留parent位置及完整消费范围，供调用者用原URI/headers/key factory解析，未伪造子请求。
- 防御复制、索引/数组/坐标/溢出及总样本数上限；不在该类推断codec RAP、audio preroll、edit
  映射或选中轨道，它也没有自行判断哪些字节已提交缓存。

该移植文件保留AOSP版权与Apache-2.0文件头，仓库及包内NOTICE同步记录，未改称MPL自有代码。
固定官方源码与摘要仍在`artifacts/m3-cached-ranges/api-audit/`。

## 独立owned AV素材

`generate-mp4-av-cache-fixture.py`固定原FFmpeg/FFprobe工具SHA，将原owned颜色视频copy与
44100Hz的440/880Hz两条AAC音轨复用为faststart MP4，原120个视频packet逐项SHA不变。
两条音轨各518个原始packet，FFprobe使用ignore_editlist导出raw PTS/DTS/duration/offset/size/
sync与payload摘要，未用Media3生成oracle。它不把raw时间表当已验证的gapless呈现时轴。

| 输入 | 字节数 | SHA-256 |
| --- | --- | --- |
| av-colors.mp4 | 176,806 | `d7b52d4d692af18d1b7264d991a7affcc1b2de599d4acdb19f979222ef1ce9fa` |
| av-colors.raw.samples.json | 322,699 | `3b468ed280fc585dc8a04f77d4b413d446b9ab882d070e53bb9b3a51e68b5186` |

两项进入测试APK后逐字节等于源文件，主APK不含这些媒体，报告为`av-assets-verification.json`。

## 实际结果和失败

首次JVM测试的同时间戳反例把10MiB样本放错了轨道，参考不符合官方“先记录累计值、再加入
所选轨大小”的顺序；按固定源码修正测试配置后，新增 **11/11** 通过。未改生产算法迎合预期。
原生对照build-01因DataReader导入包错误和无用导入编译失败，未接收旧APK；修正后build-02
冻结623项输入无漂移，完整player JVM **90/90**，lint0错误/27警告。

`Mp4ReadScheduleNativeTest`先把三个post-edit原生表的每个offset/size与独立raw packet真值核对，
再让真正DefaultExtractorsFactory/Mp4Extractor在0、4.5、9、11.9秒seek后执行到EOF。
对每次sampleMetadata核对轨序、decode-index和PTS，并对实际DataReader消费的连续坐标、
长度、RESULT_SEEK的重新open parent逐项比较。未将sampleData替换为计划，也未使用decoder。
两条audio都由native读取，不把“未选语言可以不计I/O”当作前提。

- `mp4-read-schedule-native-01`独立 **1/1**，方法内四次seek及所有后续样本比较通过。
- `mp4-read-schedule-module-01`合并原99项与上述native项，同次 **100/100，0跳过，45.718秒**。

固定`mp4-read-schedule-build-02`的player-test为27,432,620字节，SHA
`9e9a93a9ce325c3a700d28eec007c7da48f2b75449ae60bf24d72140bd807e41`；
主包474,904,799字节，SHA `db1883c5f47ffbf6effb9440df738c55fd49b38ae5a226ba15549376921a7ec8`；
app-test9,859,134字节，SHA `54a0cb572ab2e9e7f7b541be0e1f699fa30fd37984b4323294fdfac82dcb2224`。
版本字段仍0.5.18，该主包与a47f1e4整组147的主包不同，不挪用旧147或R8包证明当前输入。

## 继续完成原目标

当前scheduler尚未接入生产AV区段。须把实际main owner/选轨、audio gapless/preroll、codec init
和真实post-edit样本端点绑定；以原生调度生成所有必要字节，再做已选AV交集、停源实际
video PTS/RGB与audio PCM/语言验证和真实灰条/清理。复杂edit/PCM、其他codec/封装/fragmented、
全部startup/read重试/鉴权/跨会话以及性能/真机/Release仍保留，不以这一模型替代全P11。
