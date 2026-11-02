# MP4 双 AAC 选轨与部分缓存冷解码预验

2026-11-02，基线a5c7d4d。此批仅新增实际解码探针，**生产AV磁盘时间层仍未接入**。
范围保持完整P11，不以原生调度模型、整对象缓存或选中language字符串代替真实解码。

## 真实输入与操作

沿用176,806字节owned av-colors.mp4，SHA
`d7b52d4d692af18d1b7264d991a7affcc1b2de599d4acdb19f979222ef1ce9fa`，
120个原颜色视频packet与两条各518packet的AAC，来源与独立raw参考见读取调度记录。
每项创建独立SimpleCache及实际PlaybackSourceSession，用已提交文件保存种子与准确长度。
随后**删除原文件**，任何真正的upstream fallback都会失败，不从另一个文件补洞。

- 全量基线分别选en/ja、起始9秒，新建实际ExoPlayer、MediaCodec、AudioSink和图像输出。
- 部分缓存分别在所有三轨的独立raw PTS 4–8秒packet处留物理洞，逐段assert未缓存；
  不创建旧player或SampleQueue再复用它们，也不把语言选择替换成假的格式/结果。
- 真实TrackSelector选中输出须经生产Mp4CachedSelection绑定到当前native owner，视频＋音频
  两项分别对应ordinal[0,1]/[0,2]；检查实际语言，不能只看到完整缓存的另一条音轨就计通过。
- 视频首个frame metadata须在9秒±150000us；真实ImageReader YUV420 plane读取后按有限范围
  BT.601数值换算，中心区域蓝帧RGB每通道45阈值。未把视频ready布尔值当作图像。
- ForwardingAudioSink保留实际DefaultAudioSink，speaker volume=0避免播出测试tone，但记录音量
  应用前的已消费PCM。要求16bit/单声道/44100Hz，并在媒体9.25–9.75秒窗口取得至少18000样本，
  正向过零主频距440/880Hz≤5Hz、RMS在100–10000之间。

## 真实失败与校准

首构建因可空语言字段编译失败，未接受旧APK。首次运行的RGBA ImageReader与producer实际
0x23 YUV420格式不匹配，图像线程抛异常，0通过/进程崩溃；原runner/logcat保留。
改为实际YUV420 planes，回调异常转给测试线程记录，避免测试进程直接消失。

随后两项视频/选轨通过，但PCM窗口计数0，两项均失败。核对固定1.11.1 AudioSink/renderer
源码：sink收到的是renderer时基，需要扣除实际setOutputStreamOffsetUs回调。没有减猜测常数；
记录的offset实际为1000000000000，firstAudioSinkTimeUs为1000009009342。
校准后整对象两项2/2，再加入真实缺口，四项4/4；旧失败不删除、不抵消。

## 最终固定输入与终态

`mp4-av-native-build-05`冻结625项无漂移，完整player JVM **90/90**，lint0错误/27警告。
主包仍474,904,799字节、SHA `db1883c5f47ffbf6effb9440df738c55fd49b38ae5a226ba15549376921a7ec8`，
未改生产解码器/mapper；player-test27,628,657字节、SHA
`179cdbc0c14084dba8c16838f89b4049aec7319664022747e314e2f2ee06c8dc`。
app-test9,859,134字节、SHA `54a0cb572ab2e9e7f7b541be0e1f699fa30fd37984b4323294fdfac82dcb2224`。

- `mp4-av-native-device-03`校准后的整对象2/2。
- `mp4-av-native-device-04`含真实洞的四项 **4/4**。
- `mp4-av-native-module-01`合并原100项与上述四项，同次 **104/104，27.935秒，0跳过**。
  每项真实视频首PTS9000000，蓝帧约[0.381089,0,255]。
  en整/partial均约439.5836Hz、RMS2902.335；ja均约879.1671Hz、RMS2888.415，采样18058个。
  该整组四份原始JSON和SHA另取回该目录，不挪用前次独立运行报告。

## 尚不能推出的结论

观察到首次AudioSink媒体时间约9.009342秒，PCM主频窗口从9.25秒开始，故**没有证明精确9秒
初始AAC输出/preroll/gapless**。该基线只证明这些实际配置下的后续音轨身份和解码可行性。
尚须验证遗漏选中/未选中轨道字节的负例、原生I/O闭包与每个可展示时间单元、精确edit/时轴，
再接生产AV交集与真实灰条/清理。其他codec/容器、音轨切换、鉴权、物理机与新Release仍待。
旧0.5.18/a47f1e4的147项和打包/两项Release运行不用于证明这个新测试包或完整P11。
