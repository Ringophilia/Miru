# P11 磁盘缓存区段：实现计划与基础层验证

2026-10-31。需求保持MIRU-SPEC §8.4/8.5/P11的完整范围：从真实SimpleCache spans计算可能
不连续的磁盘时间区段，显示在内存buffer层之下，并以停源回拖验证。**P11仍pending**。
本批完成精确覆盖计算、监听基础层和唯一key factory提取，尚未接入主controller、格式适配或UI。
不能用本批单测把现有进度条称为已有磁盘区段。

## 已实现的基础层

- `CachedTimelineCoverage`接收已选轨、各时间候选的完整字节依赖和已提交spans。每key先合并
  字节范围并二分查询；候选须满足全部媒体/init/key/GOP依赖，每轨时间union后求必需AV交集。
  相邻字节可拼接，一字节洞不能补齐；不按下载比例或bufferedPosition猜时间。时间按当前窗口
  投影并裁剪；空依赖、未知长度、空轨和未知索引不能证明覆盖。容器做防御快照，区间拒绝溢出。
- `CachedTimelineTracker`仅监听当前索引涉及的key。add/remove只递增revision并投递合并失效，
  touch不改变覆盖；IO在Cache monitor下取一致的committed spans，锁外计算。选轨替换立即清旧
  值，发布检查selection身份/cache revision，close与parent cancellation清空并撤销订阅。
- 同步计算完成后再检查协程取消，避免计算中cancel先发一次旧结果；计算期间的淘汰和选轨更换
  也拒绝旧snapshot。cache异常fail closed，后续replace可重试注册；只向UI方向输出时间区段。
- `PlaybackSourceSession.cacheKeyFactory/cacheKey`提取原v2算法，实际CacheDataSource使用同一
  对象，未修改headers分区、query规则、private/offline工厂或旧缓存迁移策略。

## 固定构建与测试证据

工作树`C:\Users\tocml\.codex\worktrees\seek-preview\Miru`，基线`f98acee`加本批5项源码/测试。
`artifacts/m3-cached-ranges/core-build-01/02/`各自保存命令、全部521项输入前后SHA、原JUnit XML、
Lint XML和实际终态，源清单无漂移。PS7、显式JDK21/SDK、`--no-daemon`：

```text
:feature:player:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
```

| 执行 | 真实结果 | 范围 |
| --- | --- | --- |
| core-build-01 | 成功2分8秒，53/53，0失败/错误/跳过；lint0/22 | 原27项+覆盖核心17+监听9 |
| core-build-02 | 成功2分钟，55/55，0失败/错误/跳过；lint0/22 | 修计算中取消检查，并新增取消/真实线程竞争两项 |

核心17项包括0–4/8–12秒空洞、多轨交集、AES init/key、GOP依赖、单字节洞/邻接片段、重复span
不能重复计长、partial sample不能按比例画、VBR、淘汰/鉴权/representation与不可变输入。
监听11项包括初始snapshot、add/remove/clear、拒绝hole/未知/溢出span、选轨与key替换、close/迟到
callback、parent取消、计算期间淘汰/取消、cache异常重试、touch和无轨清理。真实线程项在IO
计算暂停时由另一线程淘汰并换轨，再恢复计算；核对旧覆盖未发布、无锁等待及订阅清理。

监听单测使用实现公开Cache契约的内存fixture，不能代替真实SimpleCache磁盘、实际播放器、
解码器、UI、private/offline或性能验收。本轮未生成/安装新的主或测试APK，旧0.5.11发布打包
与135项设备结果继续保留原源码/APK范围，不挪用于本批新源码。

## 后续实现边界与完整验收门

1. **主session与资源binding。** controller须保留实际主PlaybackSourceSession；preview单独
   刷新headers不能改主索引。记录真实原open DataSpec计算出的key及资源坐标；不可在知道EOF
   长度后重造DataSpec。无扩展名资源的`(0, UNSET)`和`(0, knownLength)`可能触发不同归一化。
   cache坐标是DataSpec.position，不盲加uriPositionOffset。所有关联仅内存，不保存URL/凭据。
2. **源/时轴/选轨代次。** source、timeline、period/window、representation及index变化均撤旧
   plan；private不取得stream cache也不建磁盘层，offline沿用独立UUID存储/无upstream工厂。
   不将任意ABR码率的缓存union冒充当前选轨可断网播放。
3. **HLS。** 包装官方playlist tracker并完整delegate，观察最终snapshot和真实DataSpec/format。
   `periodTime=playlist.startTimeUs-tracker.initialStartTimeUs+segment.relativeStartTimeUs`，
   再加period.positionInWindowUs。BYTERANGE、init/media AES key、discontinuity、独立AV须
   完整验证；未知长度仅实际EOF/可信长度闭合。LL-HLS/GAP/非独立前驱不猜测可用。
4. **DASH。** 实现DashChunkSource.Factory并完整转发设置；DefaultDashChunkSource公开
   protected representationHolders/newInitializationChunk/newMediaChunk，可在实际加载sidx后
   读index和最终BaseURL。逐segment校验RangedUri，merged load不能整体赋给每段；WrappingIndex
   已减presentationTimeOffset，不能重复减。覆盖Template/Timeline/List/Base+sidx与多period。
5. **MP4及其他VBR。** 从实际消费字节或cache-only/no-write/no-upstream reader有界组装moov，
   以公开BoxParser取样本表，同时独立保留stts/ctts/elst、逐样本duration/DTS和decode依赖。
   B帧数组非显示顺序，不能用相邻PTS算时长；按GOP/preroll/codec init和选中audio求闭包。
   head/tail、co64/extended size、多mdat、edit gap、fragmented MP4与其他VBR封装须逐格式
   证明；未知/估计索引不画，但仍保留对应实现/验收工作。
6. **真实磁盘和界面。** 0–4/8–12秒已缓存而4–8秒缺失的几何、实际请求、解码PTS/RGB和停源
   seek须吻合；覆盖一字节洞、未提交写、部分sample/segment、init/key缺失、LRU/清理、旧事件、
   同URL不同鉴权、签名与语义query、preview-only鉴权分歧、private无新磁盘和offline隔离。
   最后在PlayerScreen底轨之上、内存buffer之下绘制immutable intervals，保留P6拖动所有权。

固定1.11.1官方datasource/HLS/DASH/extractor源码包及SHA保存在
`artifacts/m3-cached-ranges/api-audit/sources-sha256.json`。
HlsChunkSource为package-private，不能按DASH继承方式实现；DASH标准Factory为final，须实现接口。
MP4公开TrackSampleTable只给offset/size/PTS/flags，不给逐sample duration/DTS，不能省略依赖解析。
完整格式、时轴、解码和UI证据齐备后，才可调整P11验收状态。
