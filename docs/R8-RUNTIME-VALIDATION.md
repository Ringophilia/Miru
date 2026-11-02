# 0.5.16 R8 启动修复与实际运行验证

2026-11-01 执行，2026-11-02归档。只证明本文明确列出的构建、载荷和两项实际运行，
不替代完整R8、ARM64物理机、正式签名或整份规格验收。

## 实际发现与修复

0.5.15的框架探针在实际解码和停源缓存重开后进入浏览器，进程崩溃于
`GeckoRuntime.init → DebugConfig.fromFile → TypeDescription.<clinit>`。
映射文件将`org.yaml.snakeyaml.TypeDescription`移到默认包`ol6`；其静态logger调用
`TypeDescription.class.getPackage().getName()`，实际抛出空指针。该失败记录在
`artifacts/r8-device-smoke/runtime-02/`，不能把此前打包成功当成浏览器可以运行。

Gradle dependencyInsight确认Gecko 158.0.20261021001945实际依赖SnakeYAML **2.2**。
从[Maven Central原始源码包](https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.2/snakeyaml-2.2-sources.jar)
核对已发布SHA-1，源码包SHA-256为
`8f7cf911cf63db55fd980a926d155bd846317737351a2f48ef1c1088c414538a`。
全源码中同类调用只有TypeDescription:43和PropertySubstitute:36两个位置。

0.5.16/code24在`app/proguard-rules.pro`仅保留这两个包的名称：

```text
-keeppackagenames org.yaml.snakeyaml,org.yaml.snakeyaml.introspector
```

类与成员仍可重命名、缩减和优化。新映射分别为`org.yaml.snakeyaml.e`和
`org.yaml.snakeyaml.introspector.e`。规则语义见[ProGuard官方手册](https://www.guardsquare.com/manual/configuration/usage)。
依赖树、原源码、摘要及映射诊断在`artifacts/r8-device-smoke/snakeyaml-audit/`和相关构建目录。

## 固定构建与包

源基线b990c4c，仅`app/build.gradle.kts`版本和`app/proguard-rules.pro`两项审查变更。
两次构建均冻结603项SHA、前后无漂移；`source-state.json`明确记录有这两项未提交变更，
**没有把它称为干净提交构建**。

| 项目 | 实际结果 |
| --- | --- |
| ARM64 R8/shrink、release lint | 3分1秒，0错误/22警告；`snakeyaml-arm64-build/` |
| 原AGP ARM64包 | 111,299,327字节；SHA `533ae95e57921e488c87a7bd2f75d7a2a917d2bac65167def1c9f2f1ce3c42bb` |
| 最终ARM64开发包 | **108,152,186字节**；SHA `f9f9e985cb8b1264355ca2e96527f071c9b1f088deaca8873cd441ae03ae9bf1` |
| x86_64 Release验证包 | 116,675,108字节；SHA `ac898dd0f9f6a5db344e1e154519afb1a00742cf3ca71c6d1c1edd05acf5f272` |

最终ARM64包严格小于110,000,000字节，1538项当前payload逐项保持、SDK 16KB对齐、
原Debug证书、17个ELF64/AArch64库及Gecko的4077原项+两项精确修改+五份固定新增资源均通过。
omni SHA为`f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`。
证书仍为Android Debug，SHA `5aa1a00e59b8638298dbddcc5a61053ca245249136edb67199e0c9a3e4cb1f84`。
unsigned scratch已在finally删除。流水线位于
`artifacts/releases/Miru-0.5.16-arm64.apk.verification-20261101-152459-d5b7f2c8/`。

x86构建仅在finalizeDsl覆盖ABI包装，保持Release优化、资源收缩和签名设置。
它与最终ARM64包的唯一`classes.dex` **逐字节一致**：10,174,204字节，SHA
`bc8fc7a4cc987f337aa8735f7f6a2c474e0c1bbbb59b32ea6afe7bfb01c621e4`。
另独立确认17个x86_64 ELF及相同Gecko资源。此对应关系证明所执行的R8 DEX来源，
不把x86原生库的运行当作ARM64原生库运行。报告在`snakeyaml-x86-build/arm64-dex-comparison.json`。

## 探针失败与最终运行

独立Java探针仅使用Android公开API、公开Activity入口和可访问的进度控件，无应用类、反射、
调试接口或测试keep规则。AGP初次隐式打包Kotlin运行库，覆盖目标已优化的Function0接口而在
WorkManager初始化崩溃（runtime-01）。禁用探针built-in Kotlin后，runtime依赖为空，DEX只定义
探针自己的类；不为这个测试污染给生产包增加keep规则。

runtime-03已实际呈现蓝帧，但探针对起始红帧的观察假定失败，未执行浏览器阶段。
改为真实公开进度操作请求1秒后检查红帧，RGB每通道45阈值保持，并不声称精确呈现PTS=1秒。
这两次失败与原始runner/签名/安装后摘要均保留。

`runtime-04` **2/2，0跳过，runner全程105.616秒**。API35/x86_64，宿主NVIDIA GPU模拟器：

- 目标为非debuggable Release，安装后pull回摘要/大小与固定输入一致；探针没有运行库类污染。
- 真实公开控件定位后，SurfaceView PixelCopy红帧RGB两次均为[255,0,0]，图片时间码约1.2/1.4秒。
  销毁前一Activity/播放器后停止HTTP媒体供应，再次打开仍解码成功；媒体请求保持首次的1次。
- 公开浏览器入口实际请求受控HTML一次，真实可访问性树出现`Miru R8 runtime content`标题。
  两个PNG、报告、完整命令、logcat及两项终态均在`artifacts/r8-device-smoke/runtime-04/`，图片已查看。

可复用框架探针与外来类拒绝校验已放入`tools/release-runtime-probe/`及配套build/run脚本。
更多操作与输入合同见该目录README。历史APK路径可按保留规则被清理，日志/摘要仍保留。

2026-11-02重新核实终态后，仓库内的build/run脚本实际执行`tracked-harness-01`与
`tracked-runtime-01`。初次外来类守卫拒绝D8自动生成的LambdaMethod；只为这个确认为annotation
的元数据类型增加精确例外，仍拒绝实际应用APK内的外来运行库类（反例报告保留）。
最终探针定义12个类，runtime依赖为空；138,044字节，SHA
`7bd002bfdd47b136d4bff7dd8ec86b3d3540354f134645ef486a59a24961d472`。
相同0.5.16 R8目标的两项实际运行再次2/2通过，报告/PNG与安装后摘要均保存于该轮目录。
此轮是在单一复用宿主GPU实例上执行，未将重启前的任何任务当作仍在运行。

## 尚未完成

0.5.16 Debug完整回归尚未运行。旧0.5.15软件模拟器145/146、宿主GPU整组139/146的失败
保持各自输入边界；后者的7项失败见benchmark阶段记录，不挪为本版通过。
本文两项运行不证明全部浏览器/媒体/私密/生命周期R8矩阵、ARM64物理设备、正式签名、
手机性能指标、全P11、M4/M5或iOS。完整目标继续保持未验收。
