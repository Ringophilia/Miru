# 120 秒 AV 磁盘灰条与索引查询优化

2026-11-03，基于7513e97，仍为0.5.19/code27。此批补长AV的实际画布和受控索引耗时，
不宣称完整P11、任意片长性能、物理机或Release验收完成。

## 固定素材与旧失败

`tools/generate-mp4-av-cache-fixture.py --long`从已提交的120秒owned视频复制全部1200个packet，
生成440/880Hz、en/ja双AAC-LC，各5169个packet。固定FFmpeg/FFprobe及逐包哈希，
11538个packet均核对，视频payload与原单视频逐一相同，无重编码。
新MP4为1749029字节，SHA `6e35f50ff508785fc92583edf117f63aee4f754ad0fe4eaefe41c29b1f27b463`；
raw参考SHA `0feea6c3df5726d6bdd29a22ec5c132f612524373cbda7e5dfd36f809ea00e74`。
两者是测试资产，不进入主APK。生成器拒绝覆盖旧输入，host证据在`mp4-av-long-fixture-preflight-01`。

`mp4-av-long-build-01`的11项AV原生测试虽然11/11，通过真实9秒蓝帧和PCM，但120秒完整缓存
索引耗时12097.691ms，重复物理洞耗时8826.627ms。实际应用两项长AV测试
`mp4-av-long-ui-before-01` **0/2**：均已有PTS9000000/RGB[1,0,255]，在原10秒门内磁盘层
仍为空。normal和真实未来503后的恢复路径都失败，runner/logcat/JSON/蓝帧保留。
该构建主APK与先前build-06逐字节相等；先前149项通过不能抵消这两项新增失败。

## 修正及最终同包结果

在一次AV映射中按实际父请求position记忆cache key，最多256项，满后只重算key；
每个唯一key仅登记一次metadata查询。保留原始父坐标、不同Range和主session鉴权分区，
不把所有请求换成一个任意key。IDR验证的记忆键改为track/sample/key三元组。
读取调度、微秒时间单元、字节依赖和所有原有阈值未改。

`mp4-av-long-build-02`冻结631项输入无漂移，JVM **90/90**、lint **0错误/27警告**。

| APK | 字节 | SHA-256 |
| --- | ---: | --- |
| main | 474931164 | `be081822b5bb794e6b1a450def12b5aef9a967cabe20f83eac4ccad2dadfffd3` |
| player-test | 30567952 | `f3adbc2790697681bc7da62ed42a8007b08ae3178c1a38f52b190c277119f544` |
| app-test | 12352979 | `bb5e24ec5f9353465606e846c37d919fede1bf52111b6ab714a6864d492c1656` |

- `mp4-av-long-module-02`同次完整模块 **111/111，JUnit76.310秒，0跳过**。不是合并独立次数。
  仍含原1908次native端点seek、选中/未选中字节洞与真实冷解码；新增120秒full/partial各一项。
- 同一模拟器的本次受控观测：完整12730单元构建 **1602.090ms**，partial 8488单元
  **1211.605ms**，分别约为原来的1/7.55和1/7.28。两种完整区段结果未改。
  这是单次观测，不是手机性能、分位数、持续负载或任意片长保证。
- library长片探针使用明确的0.5–1秒LoadControl，只测已解码的9.25–9.75秒tone窗口后暂停，
  避免将未来缺口的预取失败混入mapper耗时。生产应用测试仍使用真实15秒偏好和原503门。
- `mp4-av-long-ui-after-02`同包MP4 **8/8，JUnit67.853秒**，旧6项及新2项均通过。
  长AV重试项确实先收到未来洞HTTP503，再在显式paused seek前清空旧PTS观测，
  要求新的frame metadata回到9000000并由实际PixelCopy得到蓝帧。
- 120秒实际画布：memory=15900ms，84/90/94秒亮度分别
  **117.666664/51.666668/117.666664**，两侧比缺口高66，满足原≥30门。
  84/94秒均远于实际内存buffer，故不是把内存层当磁盘层。精确11段holes、原key、源停止后
  9秒PTS/RGB、metadata删除后同一个暂停player撤掉磁盘层/保留buffer均通过。
  原PNG、JSON和哈希另取回该轮目录，未生成逐轮APK副本。

## 完整目标仍继续

最终新输入的`mp4-av-long-routine-01`完整151项常规已启动，尚待终态；先前149/149仅对应
旧build-06。既定四个独立前置类及capability包仍排除，不算通过。新R8/ARM64打包及运行未完成。
更长片长的CPU/内存/取消与反复缓存事件、音轨切换、初始AAC preroll/gapless、精确末帧EOS，
原单视频路径的内部seek、重排/复杂edit/其他codec/封装和完整鉴权/资源矩阵仍须逐项补齐。
完整P11、其他M0–M5、正式签名和物理机门不因本批通过而缩小。
