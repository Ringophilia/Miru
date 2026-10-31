# P11 HLS 清单观察与时间依赖映射

2026-10-31，API35/x86_64，版本号保持0.5.11，**P11仍pending**。
主播放已接入官方HLS清单跟踪器的观察包装；新增时间/资源依赖映射核心，并在实际主源和磁盘上验证。
controller尚未将当前选轨、索引worker和进度条串联。本批不声称用户进度条已经显示磁盘区段。

## 实现及边界

- `ObservedHlsPlaylistTracker`包装实际Media3 1.11.1的DefaultHlsPlaylistTracker，保留同一factory的
  data sources、error policy、parser、CMCD和executor参数。全部公开方法转发，包含默认的
  deactivate方法。新增错误旁听返回中性true，不替代原有错误处理/排除策略。
- `HlsCacheCatalog`只捕获实际getPlaylistSnapshot返回的最终快照。主清单回调只更新时间基准，
  不把redirect后的baseUri当成原tracker注册URL；额外刷新只用已观察URL和isForPlayback=false。
  null最终快照撤旧值，stop/restart/owner close和迟到回调由代次隔离。最多256项，只在内存保留，
  不做磁盘IO；**存在于catalog不代表已选中该码率/音轨**。
- main且有stream cache时才安装观察包装；preview仍用原factory，private/offline没有catalog。
  `PlaybackSourceSession.close`同时清空catalog，实际传输释放继续由原player/lease负责。
- `HlsCachedTimelineIndex.track`接收一个明确选中的playlist及其原始资源resolver，按
  `playlist.startTimeUs - initialStartTimeUs + relativeStartTimeUs + period.positionInWindowUs`
  投影到窗口并裁剪。不用字节比例、bufferedPosition或相邻清单推断时间。
- 每个候选必须有完整media、init、init AES key、media AES key依赖；AES raw key须16字节且IV合法。
  BYTERANGE保留实际position/原length；未知长度只接受同原请求的已知长度绑定。短/错位resolver结果、
  时间溢出、GAP、未知时间、DRM或interstitial不产生未证明区段。LL-HLS只处理已有完整segment，
  trailing parts不伪装成完整segment。
- 非独立segment不猜“前一个足够”。核心可消费外部解析器提供的decode-start证明并纳入全部前驱依赖，
  跨discontinuity的证明被拒绝；**生产尚无提供此证明的样本解析器**，目前默认只接受清单独立声明。
  多轨结果仍由CachedTimelineCoverage求必需AV交集，不能union不同representation代替当前选轨。

## 固定构建与真实终态

工作树`G:\Projects\Miru`，基线`be34c29`加本批5项源码/模块配置/设备测试；每轮544项输入SHA
前后无漂移。后续新增本文及矩阵更新不声称是这些APK的原始文档输入。
证据在`artifacts/m3-cached-ranges/hls-index-*`，包括命令、源清单、JUnit XML、lint XML、固定APK、
runner/logcat、实际安装base.apk及SHA校验。显式Microsoft JDK21/SDK、PowerShell7：

```text
.\gradlew.bat :feature:player:testDebugUnitTest :feature:player:assembleDebugAndroidTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --console=plain
```

| 执行 | 真实结果 | 范围 |
| --- | --- | --- |
| `hls-index-build-01` | 成功2分59秒，JVM71/71，lint0错误/22警告 | 清单观察、映射核心、首批14项HLS设备用例；原JVM71项未增加Android stub替身 |
| `hls-index-device-01` | **25/25，0跳过，2.337秒** | HLS14+既有绑定11，同次执行 |
| `hls-index-build-02` | 成功3分33秒，JVM71/71，lint0/22 | 明确Unit返回以清除新增编译警告，null快照撤旧值；补null和错位/短资源负例 |
| `hls-index-device-02` | **27/27，0跳过，6.389秒** | HLS16+绑定11，同次执行 |
| `hls-index-build-03` | 成功1分48秒，JVM71项UP-TO-DATE，lint0/22 | 仅扩展独立player设备测试：真实fMP4/AES/BYTERANGE；主/应用测试APK均与build-02逐字节相同 |
| `hls-index-device-03` | **30/30，0跳过，5.819秒** | 最终固定player测试包：HLS19+绑定11，同次执行，未拼接旧结果 |
| `hls-index-app-regression-01` | **20/20，0跳过，278.542秒** | 固定主/应用测试包：Media6、Adaptive5、OfflinePlayback7、M3Tracks2；不是完整常规套件 |

应用回归在build-02包上启动，build-03只扩展另一个独立player测试包。两轮主APK与应用测试APK
SHA均相同，验证在`hls-index-payload-identity.json`；不以源码相近代替同包。最终独立测试包22项
复用素材也逐字节匹配仓库素材。各轮输入与真实终态分别保留；旧批次失败不被本批绿色结果覆盖。

| 最终产物 | 字节数 | SHA-256 |
| --- | --- | --- |
| player测试APK | 24,821,869 | `f502c8576792ce7e6cd7b21e16741b12472e753a0d6198f2e8b256e7b540d680` |
| Debug主APK | 474,712,383 | `3074edb67aaa8f46e0861deb85bbdf6fd103712ce9d054eef41a3d5989762831` |
| 应用测试APK | 8,759,267 | `81ef05366b950a3f1e0cb2e841bf7da3a82f29ce3f83a816248050abcedb7c77` |

独立设备类为`app.miru.player.HlsCachedTimelineIndexTest,app.miru.player.CacheBindingDataSourceTest`，
安装到`app.miru.player.test`；完整adb数组在每轮input.json和runner.ps1。也可运行模块全部设备测试：

```text
.\gradlew.bat --no-daemon :feature:player:connectedDebugAndroidTest
```

## 19项HLS设备证据的准确范围

官方HlsPlaylistParser驱动的受控映射用例涵盖：VBR/不等时长空洞、BYTERANGE的一字节洞与相邻补齐、
加密init+media的两把key、短/错位解析结果、未知长度/错误AES长度、显式preroll完整性与discontinuity、
必需AV交集、直播窗口两端裁剪/未知时间/溢出、GAP/LL trailing parts，以及原UNSET请求的不同key。
这些用例使用明确的资源绑定与spans模型，不冒充实际网络/解码/磁盘证明。

两个tracker契约门覆盖每个公开方法的实际转发、最终snapshot原对象、redirect原URI、stop/restart/
owner close及迟到回调；另有null最终快照即时撤旧值。这里使用公开接口proxy，不替代真实tracker。

真实主source门另用实际DefaultHlsPlaylistTracker、ExoPlayer、生产资源绑定和SimpleCache：

- TS三段完整覆盖0–12秒；移除真实中间资源后为0–4/8–12秒；清除全部依赖后为空。
- fMP4完整覆盖后删除828字节init，剩余媒体仍在磁盘但所有候选撤销。
- AES-128完整覆盖，首段实际缓存13,552字节密文、16字节原key；移除key后撤销区段。
- BYTERANGE把三份原TS字节拼成同一资源，实际原请求为0/13,536/26,508；中段12,972字节span
  移除后保留0–4/8–12秒。没有按container整体长度或读取比例猜测。
- 实际preview可以写缓存但不写主catalog；private/offline实际HLS来源不建catalog也不写stream cache。
  此门使用本地受控文件，不替代全生命周期/公网/原生provider审计。

## 派生清单的独立性证明

复用原`media/seek-preview`自有素材，原清单、媒体和manifest.json均未修改；模块androidTest专用
asset目录复用这22项文件，不加入生产assets。测试运行时才生成带INDEPENDENT-SEGMENTS声明的
派生清单，以及直接拼接TS的BYTERANGE清单。

声明有独立素材依据：`hls-fixture-independence/`固定FFprobe SHA
`3ca44c072baeb38eaf21f32f8bf527718c5cd2ee0bc49122e187b7c1ab595644`、FFmpeg SHA
`6d1169549f6a67de04dfc1d1868b514d594e365a1104fd384cbe851ded7108f8`，二者均为原固定n8.0.1工具。
三TS、三init+fMP4、三公开测试key解密后的TS，首包均为K；九份分别独立解码的首帧RGB全部达到
原45/channel门，分别为红/绿/蓝，未删除前驱包迎合声明。AES解密只使用仓库公开00..0f测试key及
原IV1，采用CBC/PKCS7；完整派生产物、命令、首包PTS/flags、raw帧和SHA保留。
这些是**宿主素材独立性证明**，不等于Android磁盘seek的解码PTS/RGB或整个P11验收。

## 继续推进的完整门

1. controller须把实际Tracks/downstream format、period/window和当前playlist关联，串联IO worker、
   原资源snapshot、catalog revision与CachedTimelineTracker。ABR、独立/复用AV、重排序与冗余URL
   必须按实际选择处理；catalog出现过的所有playlist不能当成当前选择。
2. 跨会话已缓存但本次尚未open的未知长度资源，以及中断后的Range重试，仍需正确利用原key和可信
   cache元数据闭合；不能从失败前缀或EOF后的重造DataSpec恢复错误key。
3. 非独立segment的真实sample/GOP证明、LL-HLS parts、动态多轨/直播、加密init实际来源和特殊清单
   仍待，不以显式测试proof map替代生产解析器。
4. 完成真实Android解码PTS/RGB、停源回拖/空洞失败、淘汰/清理/旧事件/鉴权失效和进度条几何验证；
   新UI必须位于内存buffer之下，保留P6拖动所有权。
5. DASH实际索引和MP4完整样本/编辑/GOP映射继续保留原计划，不能以本批HLS部分替代所有格式。
   本批没有新ARM64/R8运行、正式签名或全量验收声明。
