# 0.5.18 Progressive 准备与初始 seek 调度

2026-11-02，基线231c6fb。保留P9/P11完整目标，本批不等于全格式/全时轴或整个阶段完成。

## 失败与固定官方实现

0.5.17的`cached-seek-final-ui-02`为13/15：短片停供后虽然实际呈现9秒蓝帧，却额外请求
[8482,15032)中段/503，违反原零新增上游门。另一个超分起播失败也保留，不以本批替换旧结果。

检查固定Media3 1.11.1原源码发现：`ProgressiveMediaPeriod.endTracks/seekMap`向playback
looper投递准备回调；加载线程继续执行extractor.read。`maybeFinishPrepare`才关闭loadCondition，
初始非零位置在选轨后经原生seek map重新打开。默认检查间隔为1MiB；小文件的加载线程可能
在准备/初始seek处理前就越过缓存前缀。对应源文件和官方来源SHA保存在
`artifacts/m3-cached-ranges/api-audit/`，不根据一张截图推断此原因。

## 受控因果验证

`Mp4StartupSchedulingTest`使用原DefaultExtractorsFactory/Mp4Extractor、真实SimpleCache和
CacheDataSource，固定原owned 21,552字节短片及独立样本表，不替换native解析或seek。
加载线程发出metadata时，仅在专属playback looper上暂缓准备回调，再让真实加载线程继续：

- 默认1MiB配置实际向已停供的upstream请求8482，证明在初始seek前可以先读入未缓存中段。
- 4KiB配置在缓存前缀处归还调度；解除同一准备门后，真实seek map在独立9秒样本offset打开，
  没有向upstream请求。已提交spans、缓存洞、真实原DataSpec和最终目标位置均检查。
- 固定prepare门有界等待，结束后释放player/专属looper/cache；负例的upstream是记录真实
  CacheDataSource.open的停供fixture，不把它说成实际HTTP服务器或解码/像素验证。

`startup-gate-probe-01`主包仍为原0.5.17，新增两项模块测试；
`startup-gate-probe-device-01`实际 **2/2，0跳过**。两种配置均已执行，非只陈述源码可能性。

## 生产接入

普通流主播放Progressive factory使用公开`setContinueLoadingCheckIntervalBytes(4096)`。
这是每次extractor.read返回后的继续加载检查间隔，不是缩短DataSource读取或sample大小，也
不是伪造EOF、跳过字节或改变cache key。原生代码继续负责准备、选轨、seek与加载控制。
HLS/DASH、private/offline以及preview原工厂路径保持各自行为；当前未对它们改同一配置。

该调度改变仍需大码率、其他progressive封装、吞吐/功耗及物理机验证；不能用小AVC fixture
声称所有原生startup I/O闭包已经完成。

## 最终固定输入与实际结果

`startup-yield-build-01`：616项源输入前后SHA相同，构建2分15秒，JVM79/79，lint0错误/27警告。

| APK | 字节数 | SHA-256 |
| --- | --- | --- |
| main.apk | 474,467,873 | `a30a75fe0bd8b07c2ded0aaf1d1ad0aa5fbede370a5e3f0195a49a6d7410e6cd` |
| app-test.apk | 9,858,949 | `20ee93354113f762b983ca70fbf43e241eac48a4ccecc927cbb2ed9cd04829f1` |
| player-test.apk | 27,428,550 | `b3308571f9b2bd9fef65d3da3a4ea4907e6d3eb4689508630d7ca5043e90a125` |

- `startup-yield-module-01`同次 **99/99，32.046秒，0跳过**，包含原97项和因果验证两项。
- `startup-yield-ui-01`固定同包 **15/15，135.838秒，0跳过**：鉴权五项、MP4四项及其他六项
  均在同一次选择中通过。短片只有seed三次HTTP调用，冷9秒PTS9000000/RGB[1,0,255]且无新增
  上游；长片和503后恢复保留全部holes、原像素门及metadata清理。绿色缺口仍请求503且不画可用。
  超分起播本轮通过，但未收集到旧失败状态的唯一根因，不能声称所有迟到repeat/起播问题已修复。
- 七份原始JSON/PNG与SHA取回于该UI目录，`proof-files.json`绑定实际证据，没有把其他轮次PNG
  当作当前结果。模拟器为API35/x86_64、单一软件渲染实例，不替代真机性能。
- 完整常规147项`startup-yield-routine-01`已实际启动，**尚无终态，不预记通过**。
  四个独立前置类和capability包仍排除，不计通过。稀疏实时benchmark/错误捕获绑定真实runner
  PID/start-time，终态后自动结束捕获，不凭旧lease猜测进程存活。

新R8/ARM64打包和运行、正式签名、全P11格式与生命周期、手机性能及M4/M5/iOS仍保持未完成。
