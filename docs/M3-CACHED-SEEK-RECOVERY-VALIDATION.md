# 0.5.17 暂停状态下的缓存回拖与源错误恢复

2026-11-02，基线d1dcae4，P9/P11仍保留完整验收范围。本批不是全格式、完整生命周期、
物理机或R8/发行完成声明。

## 原始证据

0.5.16固定Debug `r8-fix-debug-01`冻结614项、JVM79/79、lint0/27。原7项失败方法在宿主GPU
复现时，前三项status0后qemu在nvoglv64.dll中崩溃，无JUnit终态；Windows事件与中断记录保留。
改用同一AVD的单一软件渲染实例，`r8-fix-seven-software-01`为 **6/7，136.975秒**。
两项目录、离线通知、拖动预览、超分切档/旋转及网页媒体通过；长MP4在等待9秒PTS处失败。
不能把这六项与其他轮次拼为整组通过，也不追认旧139/146全部为宿主原因。

该长MP4报告显示先ready/position11553ms，暂停回拖后仍为位置9000、error=true、native IDLE，
PTS停在11600000。新捕获的原生日志明确记录ExoPlaybackException Source error、HTTP503。
请求全部来自未来未缓存绿色段，目标蓝段与metadata仍完整缓存。

另一次同包单独长片运行`r8-fix-mp4-long-trace-device-01`通过9秒PTS9000000、RGB[1,0,255]
和完整11段backend区间，但即时Canvas截图84/90/94均为51.666668而失败。这与前一次源错误
不是同一失败；Compose绘制与backend发布须分别观察。

## 先证伪，再修改

新增方法`pausedCachedSeekRecoversAfterFuturePrefetchFailure`，用真实HTTP/CacheDataSource种子、
真实缺口与503，先等待原生错误码2004/HTTP503，再暂停并显式回拖缓存9秒。
`cached-seek-red-01`仍是原0.5.16主包`fb6f0783...`，只新增测试/诊断；JVM79/79、614项无漂移。
`cached-seek-red-device-01` **0/1，20.996秒**，原生seek前后均IDLE/error2004，10秒PTS门失败。
原包、所有请求/状态和JSON保留，不用人为抛错代替原生源错误。

## 生产修正与测试合同

- 显式seek遇到HTTP5xx源错误时，保持同一ExoPlayer、同一source/session及paused/playing意图，
  seek后prepare以重新启动已停下的源。没有自动循环重试，不刷新或替换headers。
- 401/403鉴权、解码、DRM等错误不进入这个HTTP5xx分支，保留原专门处理；释放/返回网页后不响应seek。
- 清除本次旧错误/超时/ready状态，并重设20秒启动界限；定时器检查player身份与source代次。
- 测试保留原PTS±150000us、RGB每通道45、完整11段holes、仅未来缺口503、memory<80秒、
  84/94相对90秒的亮度差≥30、metadata清理不换player/恢复播放/清native buffer等全部门。
- 仅为真实画布增加最多5秒的绘制观察，保留同一像素门。等待503超过原自动隐藏时长后，
  新用例通过真实视频点击显示控制层，不修改Compose内部状态。

首修正`cached-seek-fix-01`的鉴权5项+MP4四项为 **8/9，64.879秒**。新增用例已通过9秒PTS、
RGB蓝帧和完整holes，却在控制层隐藏时等待track节点失败；原失败完整保留。
补真实点击的build-02因测试局部source变量遮蔽MotionEvent.source而编译失败，不接受旧APK；
显式this.source修正后build-03成功。

## 当前固定输入与实际终态

阶段`cached-seek-fix-03`为614项无漂移、构建1分26秒、JVM79/79，生产主包与fix-01逐字节相同。
`cached-seek-final-ui-01`同次 **14/15，134.966秒**：新增恢复用例完整通过，保留暂停、9秒
PTS/RGB、完整holes、像素及metadata清理门；原长片却出现PTS9但PixelCopy黑色、无源错误，
不与前次HTTP503误判为同因。原稳定布局条件仅检查configuration横屏，随后补实际decor窗口
宽>高，并记录暂停时真实窗口/Surface尺寸；这是控制该缓存专项的布局前提，不声称解决全部旋转。

最终`cached-seek-fix-04`冻结615项、构建1分29秒、JVM79/79、lint0/27，生产主包仍逐字节相同。

| APK | 字节数 | SHA-256 |
| --- | --- | --- |
| main.apk | 474,467,873 | `f8b957d9eac34182c084fa02a84ace62127b2eca1a10de40c0a72882c9e95911` |
| app-test.apk | 9,858,949 | `20ee93354113f762b983ca70fbf43e241eac48a4ccecc927cbb2ed9cd04829f1` |
| player-test.apk | 27,428,221 | `dc910b0cd9a0b09f943bb4dc700628c65f7533a7a87ef59a54b4145d5f6260e3` |

`cached-seek-final-ui-02`选择鉴权5项、MP4四项和旧失败的其他六项共15项，实际 **13/15，
163.870秒**。新增恢复、长片显示、绿色缺口、全部鉴权5项及其他五项通过；短片9秒虽然实际
PTS9000000/RGB[1,0,255]，但额外请求一次[8482,15032)绿色缺口/503，违反原零新增上游门；
超分切档/旋转用例则在其初始播放器30秒门失败。两项不排除、不放宽也不记作通过。

恢复用例两轮均原生error2004/503后以同一暂停播放器重新取得PTS9000000、RGB[1,0,255]、
11段holes，84/90/94亮度117.666664/51.666668/117.666664，metadata清理仍保留player和buffer。
实际报告在各轮`mp4-ui-cold-retry.json`。短片不必要的启动读、快速暂停/旋转及完整时序稳定性
继续诊断，不能将缓存区段计算正确替代所有原生startup I/O和实际绘制。

原生日志分别在`cached-seek-final-diagnosis-01/02/`；`cached-seek-module-final`另同次 **97/97，30.261秒，0跳过**，与应用15项各自独立记录。完整常规应为147项，新R8/ARM64运行和打包、全部网络错误/缓存IO/跨格式与时轴
矩阵仍待，不以这批受控结果调整完整P11状态。

设备中断后runner原本仍查询logcat并可能挂起。现两种runner在instrumentation传输非零退出时
保留原失败而跳过追加查询；host mock验证成功、坏pull、错计数和transport-lost四条路径，
均核对临时APK/lease清理。它不把观察超时当作测试成功，也不主动重启设备或重跑用例。
