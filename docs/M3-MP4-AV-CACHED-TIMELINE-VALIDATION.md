# 0.5.19 MP4 AVC/AAC 磁盘时间区段

2026-11-03，生产候选基于879835e，版本0.5.19/code27。完整P11和全规范验收保持未完成。

## 实现与实际反例

`Mp4AvCachedTimelineIndex`接入原有生产MP4索引入口。先验证当前native owner的metadata读取签名，
再核对公开BoxParser样本表、原始整数tick时序、选中视频和AAC输出身份。此批支持无视频重排的
AVC/AAC-LC配置、能逐样本精确对齐的单edit；复杂edit、重排/其他codec等继续保留实现工作。
依赖包含初始化metadata、选中视频的实际IDR、选中音频，以及原生extractor为了到达它们而读取
的未选中轨道和物理间隙。共享不可变读取布局，避免每个时间单元重复构造累计样本字节数组。

初始候选的4项解码/区段检查和两项AV界面检查虽通过，内部时间反例仍发现漏项：
`mp4-av-interior-red-01`移除选中AAC的字节136864，新的实际player在9.010秒因原文件已删除而
报错2005，但候选仍发布[0,9.032562)秒。该轮0/1，原APK哈希、runner、logcat和JSON均保留。
未将边界9秒可播放当作整个区间可播放。

修正依据固定Media3 1.11.1的真实decode-only规则：输出PTS早于seek目标时不能直接当作首个
可输出样本，视频已知末帧有EOS例外。时间划分包含精确PTS后的1微秒边界；每个时间单元的
依赖覆盖其最后一个微秒所需的首个后续音/视频样本，同时保留该单元起始的原生seek索引和
父请求坐标。所有轨道的PTS/结束边界都参与划分，未选中语言不能从物理读取闭包中消失。

## 固定输入与独立读路径核对

沿用owned `av-colors.mp4`，176806字节，SHA
`d7b52d4d692af18d1b7264d991a7affcc1b2de599d4acdb19f979222ef1ce9fa`。
独立FFprobe raw packet坐标与tick参考未改。真实SimpleCache提交后删除原文件，fallback必然失败。

- en/ja整对象与真实4–8秒三轨packet洞：新decoder、9秒视频PTS和YUV蓝帧；真实sink的
  9.25–9.75秒PCM至少18000样本，440/880Hz误差≤5Hz，RMS门不变。
- 选中AAC字节135324、未选中AAC字节135472、内部seek所需视频字节137886与AAC字节136864：
  先释放旧player，再创建新player，四项均实际报错2005，对应时间不再被覆盖；补回该字节后
  生产区段恢复完整[0,12)秒。补回后的区段检查不冒充又一次冷解码。
- 独立`Mp4AvNativeReadOracle`不调用Miru调度器或目标样本查找。对生产计划的1272个时间单元
  分别测试起点/最后微秒（1微秒单元只测一次），**1908次实际native seek**，核对**51067次
  实际读取、16443241字节**均包含于该单元的原父请求/key字节证明。此项证明extractor读取，
  不宣称1908次都执行了真实硬件音视频解码。

`mp4-av-index-build-06`冻结628项源码无漂移，JVM **90/90**，lint **0错误/27警告**：

| APK | 字节 | SHA-256 |
| --- | ---: | --- |
| main | 474484397 | `72a6995675ebf68f604fd2097ea62b40b518e7f143039c7d2c844a2d8188a94c` |
| player-test | 27648395 | `87a56f779f097c1399951069a9d1ba2b639636413c5aa39206a3a895e5b0183d` |
| app-test | 9862283 | `cdb35a864d05f67c99c7dc4643b13d3cec24abc5744291cd5db8c7780a92e061` |

同包模块`mp4-av-index-module-01` **109/109，JUnit66.099秒，0跳过**，包含上述9项AV及原100项。
不是将独立运行相加。安装字节/哈希相等，无保留的临时pull或逐轮APK副本。

## 界面与仍需完成

前一生产输入build-05的完整6项MP4界面检查通过，含旧4项和新AV两项。
停源9秒实际PixelCopy蓝帧RGB[1,0,255]，原生选中en；生产发布
**[0,3.900001)与[9,12)秒**，删除metadata后同一个暂停player的磁盘层撤回，内存buffer保留。
3.9秒之后的内部seek需要缺失的4秒视频帧，故不能沿用旧的[0,4)端点。
5秒seek确实在物理洞收到503且没有相应视频PTS。原单视频的端点和测试门未修改。

最终build-06的`mp4-av-index-ui-01`同包17项应用专项 **17/17，JUnit202.295秒**，含HLS/DASH、
MP4六项、上传、离线通知、预览、超分切换和网页交互。新AV两项的JSON和PNG另取回该轮目录。
完整149项常规首轮`mp4-av-index-routine-01`仅记录第1项启动，无通过或JUnit终态；恢复时runner、ADB instrumentation与模拟器进程均已消失，原因未定，另存中断观察记录。恢复WfmAdApi35软件GPU实例后，同一冻结包的`mp4-av-index-routine-02`完成 **149/149，JUnit1114.743秒，0失败/跳过**，runner1118.874秒、ADB0、149个status0及终态OK。四个独立前置类（DownloadRestartTest、OfflineRestartTest、RuntimeSitePermissionIntegrationTest、AudioCaptureIntegrationTest）与capability包仍按原规则排除，不算已通过。
短AV截图会受已播放/内存层遮挡，尚不作为独立AV磁盘像素几何证据；须补长AV、多语言切换、
长期片长下的构建耗时/取消与内存。精确初始AAC preroll/gapless、复杂时轴/重排/其他封装、
完整鉴权与跨会话矩阵、物理机与新R8/发行门仍待。旧0.5.18的147项和Release不用于证明本包。
