# Benchmark 采样与 EGL 释放阶段期限

2026-11-01，基线df86c7f/0.5.15。此批只修复已完成采样之后的阶段计时，不把原完整回归
145/146或模拟器同步render耗时写成物理GPU性能通过。

## 两种不同的原始失败

固定mp4-ui-build-07/main `b7561783...`的完整常规为145过/146，JUnit1133.900秒。
QUALITY在COLLECTING阶段45206ms超时，只有37帧，平均同步render时间871.2109ms。
FAST/BALANCED释放回调各449/505ms，故这轮不是卡在释放；采样不足保持失败。

同包隔离benchmark两项为1过/2，JUnit112.519秒。QUALITY在44722ms已取得60帧、平均
586.5273ms，但在AWAITING_RELEASE阶段45177ms被同一个45秒期限判为“有效帧不足”。
真实释放回调45436ms才到达，耗时689ms。这说明有效采样与EGL释放共用期限会使已完成
测量被误报为采样不足，不说明整组37帧的那次也属于同一原因。

原始input/runner/result在`mp4-ui-routine-final/`与`mp4-benchmark-isolated-01/`，
完整阶段流和两次run的解析记录在`mp4-benchmark-diagnosis-01/benchmark-live.log`、
`events.json`。路径均相对于`artifacts/m3-cached-ranges/`。源包与阶段run逐一关联，未修改旧日志。

## 修改后的合同

- 初始化/采样仍沿用原45秒期限，仍须同一renderer generation、60个实际渲染帧、720P输入、
  1920×1080输出及有效的真实计时。缺帧、shader/decoder错误与过热不会转成成功。
- 仅在该期限内完成有效采样后，进入独立的10秒EGL释放期限。重复进入不能续期，过期或
  时钟回退不能复活；迟到释放回调在重新启动下一档前仍核对epoch、错误/关闭状态和期限。
- 释放超时使用独立的中英文错误，避免说已取得的60帧“不足”。推荐仍为每帧<16ms的最高档，
  查看结果不改偏好，取消/后台仍释放真实player和GL线程。
- UI测试仅增加对输出等待、45秒采样和10秒释放终态的观察时间，保持真实60帧/计时/推荐/
  偏好/线程退出断言，并把新的释放超时文案也列为失败。不以等待更久把采样不足当通过。

## 固定构建与当前结果

`benchmark-release-build-01`由仓库的`tools/build-player-validation.ps1`实际执行，602项源码
输入前后SHA一致。构建成功5分36秒，player JVM **79/79**，0失败/错误/跳过，lint **0错误/27警告**。
新增5项纯逻辑测试覆盖：实际44722/45436ms重放、原45秒采样边界、10秒释放边界、重复/迟到
不能续期，以及下一档独立期限/单调时钟回退。它们不代替真实EGL或设备测试。

| 固定APK | 字节数 | SHA-256 |
| --- | --- | --- |
| main.apk | 474,467,877 | `53d137a72beb2d8ff8440673b65d4a0537d86e778af92cae56ebfdb0c266cebf` |
| app-test.apk | 9,138,977 | `528936313d1d36ef81ba93aff1b9a1ecdf4c1ee58cc2773812161de257d2cd89` |
| player-test.apk | 27,012,935 | `220cdfad0d32c1c161204677b3ceb3c448f9ced019c6222f711130dd889c6c8a` |

`benchmark-release-ui-01`实际两项benchmark专项为 **1过/2，1失败**，JUnit167.690秒，
阶段流另存于`benchmark-release-diagnosis-01/`。QUALITY在COLLECTING阶段45166ms超时，
只有25帧，平均同步render时间1152.4157ms；采样门未被释放修复放宽。BALANCED在41432ms
取得60帧，其释放回调540ms后到达，日志确认已使用新的独立释放期限。

该实例明确使用Google SwiftShader软件渲染。宿主机有NVIDIA GPU，随后结束已终态测试，
重启相同API35/x86_64验证AVD为`-gpu host -read-only`，继续按同一固定APK/帧数/画质验证。
启动命令与GPU模式记录在`artifacts/r8-device-smoke/host-gpu-primary/`；初次并行只读启动因
旧写入实例锁定AVD而失败，日志也保留。宿主GPU模拟器仍不是Android物理SoC或ARM64真机。

`benchmark-release-host-ui-01`固定同包 **2/2通过**，0跳过，JUnit28.231秒，runner32.144秒。
实际GLES为NVIDIA GeForce RTX3080Ti Laptop GPU经Android Emulator OpenGL ES Translator。
FAST/BALANCED/QUALITY分别取得62/63/61帧，平均同步render时间26.6740/18.1416/22.0128ms，
均不满足<16ms推荐门，界面正确无推荐且偏好不变。取消、重开、后台中断及线程退出用例也通过。
阶段流和graphics.json另存于`benchmark-release-host-diagnosis-01/`，不把它挪作SwiftShader结果。
这轮证明新输入的实际benchmark功能，不单独证明44.722秒边界的真实EGL重放；该边界由原失败
日志及新增确定性单测覆盖。原软件实例的采样不足与所有历史失败继续保留。

新输入的模块97项、缓存界面11项及完整常规146项已在同一宿主GPU实例依次启动，
**尚未取得完整终态，不预记通过**，证据前缀`benchmark-release-host-*`。

旧输入97/97、11/11与145/146不挪用于这组三个新APK。新输入的完整常规、R8/ARM64、
正式签名与物理性能仍待。
