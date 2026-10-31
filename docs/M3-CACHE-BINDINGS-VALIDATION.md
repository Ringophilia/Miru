# P11 主源资源绑定与真实磁盘基础验证

2026-10-31，API35/x86_64。生产版本号保持0.5.11；本批不是新的发行包，**P11仍pending**。
从实际主源open记录原始缓存键、字节坐标和可信长度，尚未建立HLS/DASH/MP4时间索引或绘制磁盘层。

## 实现

- `CacheBindingDataSource`包装实际主播放的外层CacheDataSource，在转发open之前使用同一个
  `PlaybackSourceSession.cacheKeyFactory`计算key。坐标始终为原DataSpec.position，不叠加
  uriPositionOffset，也不在知道长度后重造DataSpec。原有缓存命名空间、headers摘要和query规则不改。
- `CachedResourceBindings`仅在内存保存精确URI/原position/原length的绑定；最多4096项，淘汰旧项
  只减少证明，不猜测替代绑定。快照不可变，revision可供后续格式worker失效；不保存请求头、响应头
  或凭据，不打印URI。已知请求长度须完整覆盖，未知长度只能来自成功open的已知长度或实际EOF；
  提前close不推断EOF，空资源/溢出/短读/读取异常不能建立完整范围。
- 新open立即撤旧绑定，读取失败终结本次observation，迟到open/EOF不能复活失败前缀；owner close同步清空并拒绝
  后续回写和新factory。已有source的传输由原player/lease释放，不能以元数据close代替socket取消。
- controller保留实际主PlaybackSourceSession，在重建、回网页和release时撤旧元数据。preview的
  独立factory不写主绑定；private无stream cache，offline显式忽略传入的stream cache。
  这些绑定描述资源要求，**不证明已经提交到磁盘**，仍须由CachedTimelineTracker核对committed spans。

## 固定输入与构建

工作树`G:\Projects\Miru`，输入基线`d5619a2`加本批7项源码/测试/模块配置。
证据位于`artifacts/m3-cached-ranges/`：前两轮build各540项，第三轮含新增本说明共541项，均有完整源文件前后SHA、无漂移检查、命令、
JUnit XML、lint XML、固定APK及终态；设备run另存runner、logcat、实际安装base.apk和SHA校验。
后续本说明及矩阵更新不声称是冻结APK的原始文档输入。

显式Microsoft JDK21、Android SDK、PowerShell7：

```text
.\gradlew.bat :feature:player:testDebugUnitTest :feature:player:assembleDebugAndroidTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --console=plain
```

独立player测试安装到自包含的`app.miru.player.test`，不会启动Gecko；原始执行命令保存在各run的
input.json及runner.ps1。重跑设备类可用（adb取SDK platform-tools）：

```text
adb -s emulator-5554 install -r feature/player/build/outputs/apk/androidTest/debug/player-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class app.miru.player.CacheBindingDataSourceTest app.miru.player.test/androidx.test.runner.AndroidJUnitRunner
```

应用专项使用固定主/应用测试APK，selector精确为`MediaIntegrationTest,AdaptivePlaybackIntegrationTest,SeekPreviewIntegrationTest,OfflinePlaybackIntegrationTest`，均在`app.miru.browser`包下；完整参数数组和实际安装SHA同样在各run的input.json。

| 执行 | 真实终态 | 范围 |
| --- | --- | --- |
| `bindings-build-01` | 成功3分36秒；播放器70/70，0失败/错误/跳过；lint0错误/22警告 | 原55项+绑定15项，主/应用测试/独立player测试APK |
| `bindings-device-01` | 初始化失败，实际用例0项执行 | 一个表达式测试方法被推断为返回List，JUnit要求void；保留完整initializationError，不记为10项执行或通过 |
| `bindings-build-02` | 成功2分38秒；播放器70/70，0失败/错误/跳过；lint0错误/22警告 | 修测试返回类型及等待tracker清理，补session关闭后拒绝新factory和迟到source门 |
| `bindings-device-02` | **10/10通过，0跳过，runner 1.166秒** | 固定独立player测试APK，在真实SimpleCache上的绑定、覆盖基础和实际session路径 |
| `bindings-app-regression-01` | **23/23通过，0跳过，runner 180.774秒** | build-02固定主/测试包：Media6、Adaptive5、SeekPreview5、OfflinePlayback7；不是完整常规套件 |
| `bindings-failed-eof-red` | **新增反例1项执行/1失败，0跳过**，Gradle exit1 | 修复前实际读到失败后迟到EOF把128字节前缀重建为完整范围；保留JUnit与原始断言 |
| `bindings-build-03` | 成功2分50秒；播放器**71/71**，0失败/错误/跳过；lint0错误/22警告 | 失败时移除原observation，包装器不再向失败open提交事件；新增JVM与设备反例 |
| `bindings-device-03` | **11/11通过，0跳过，runner 2.090秒** | build-03固定独立player测试包，包括原10项与真实CacheDataSource失败后迟到EOF门 |
| `bindings-app-regression-02` | **23/23通过，0跳过，runner 180.213秒** | 最终build-03固定主/应用测试包，同次4类应用专项完整通过；不是与此前轮次拼接 |

最终build-03固定产物（前两轮产物及SHA均保留在各自result.json，不合并其测试输入）：

| 产物 | 字节数 | SHA-256 |
| --- | --- | --- |
| player测试APK | 24,810,934 | `4f48672f3e9797dca4880cde52b959142f0dcacb43ff18a4c27c5a9b512d2fb7` |
| Debug主APK | 474,702,493 | `242354b6dc6b59caf7f76913b1157d3a9582e90753d73e9e89c1150c2cf8e4dd` |
| 应用测试APK | 8,759,267 | `81ef05366b950a3f1e0cb2e841bf7da3a82f29ce3f83a816248050abcedb7c77` |

## 真实磁盘与session的11项门

1. 原position33/length173/uriPositionOffset700，实际读回原字节，磁盘仅33处命中，733处不命中。
2. 无扩展名、仅签名query的原(0,UNSET)请求，实际EOF后仍保留原key；重造knownLength请求的key
   确实不同，不可误用于查找。使用明确返回未知长度的受控byte source，通过真实CacheDataSource落盘。
3. 未知长度只读128字节后close，真实128字节已经提交，整体长度绑定仍空。
4. 已知整体长度和正在写入的128字节不产生committed spans；close后的部分span仍不覆盖整体时间。
5. 真实磁盘前/后各32字节映射到受控0–4/8–12秒，中间32字节未提交时保持空洞；commit后变0–12秒，
   remove中间span恢复空洞，removeResource清空。使用生产tracker和真实监听，不是内存Cache替身。
6. 上游在128字节后真实抛读异常，close可以留下实际prefix，但整体绑定被撤销。
7. 实际主factory/ExoPlayer读取自有PCM WAV并建立完整长度；owner关闭清空，预先创建source的迟到open
   不能复活绑定，新主factory和preview lease均被拒绝。
8. 实际preview factory/ExoPlayer可以填充磁盘，但主绑定仍0项、revision0。
9. private和offline的实际factory/ExoPlayer读取WAV，stream目录无条目，绑定0项/revision0；offline即使
   显式传入stream cache仍隔离。此项是受控文件来源门，不替代公网/私密全生命周期审计。
10. 同URL两个不同Authorization会话，通过实际缓存源读回完整不同partition条目；key不含凭据明文。
11. 未知长度上游先读128字节、随后抛IOException、再返回迟到EOF，通过真实CacheDataSource继续转发，
    原失败open仍不能形成128字节“完整资源”。模型另验证同observation迟到opened也不能复活，只有新open可以重试。

以上time区间来自测试明确指定的字节依赖，**不是已完成格式解析**。WAV用于验证实际source/session
路径，不证明视频解码PTS/RGB。未知长度和错误使用受控DataSource，不能声称已覆盖HTTP截断/redirect矩阵。

## 后续门

HLS tracker和独立AV/AES/init/BYTERANGE、DASH实际index/BaseURL/merged loads、多period与MP4逐样本
呈现/解码依赖仍待；主源timeline/选轨代次、真实视频缓存空洞与解码/停源seek、UI磁盘层及私密/离线
完整审计仍须继续。4096项内存上限会保守省略超出绑定，不允许据此填充未证明区间。
旧0.5.11的135项整套回归与ARM64/R8打包仅覆盖旧输入，本批未沿用其通过状态。
