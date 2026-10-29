# ARM64 APK 体积研究（待执行优化）

2026-10-24。此文包含打包路径研究和后续未签中间产物实测，不是完整 PERF08 / 发布验收通过记录；最终签名与 release 运行验证仍须单独取证。

## 当前证据

`artifacts/browser-core/release-apk-verification.json` 记录 0.5.1 / versionCode 9 的 ARM64 release APK：

- 111,060,934 字节，SHA-256 `a34b9692d99845b983f90bccc3b045a3d30a9943517d41e5aad5fe2ef8d4939e`。
- 超过十进制 110,000,000 字节上限 1,060,934 字节；严格 `<` 要求至少减少 1,060,935 字节。
- 已验证 `omni.ja` 原 4079 项和官方 blocklist 新增 5 项的载荷完整性。

只读取该 APK 的 ZIP 中央目录得到下表。压缩字节不包含 ZIP 头、签名块和对齐填充，不能用其合计替代 APK 文件长度。

| 分类 | 条目 | 原始字节 | 压缩字节 |
| --- | ---: | ---: | ---: |
| `lib/` | 17 | 198,841,664 | 83,703,911 |
| `assets/` | 47 | 21,971,918 | 20,686,471 |
| DEX | 1 | 9,823,856 | 4,756,510 |
| `res/` | 1308 | 1,369,114 | 656,134 |
| 其余 | 143 | 1,230,804 | 1,060,974 |

其中 `libxul.so` 为 153,828,744 → 65,269,870 字节，`libmegazord.so` 为 22,158,120 → 8,459,190 字节，`omni.ja` 为 16,459,034 → 15,726,655 字节，原签名 uBO XPI 为 4,650,100 → 4,570,376 字节。主要优化空间应先由原生库的外层 DEFLATE 实测决定。

## 已有配置与受支持入口

当前 release 已单 ARM64、启用 R8 / resource shrinking，并通过 `JniLibsPackaging.useLegacyPackaging=true` 压缩 `.so`。DEX 也已压缩；重复设置 legacy packaging 不会产生额外收益。[JNI API](https://developer.android.com/reference/tools/gradle-api/9.1/com/android/build/api/dsl/JniLibsPackaging)、[DEX API](https://developer.android.com/reference/tools/gradle-api/9.1/com/android/build/api/dsl/DexPackaging)。

从 Google Maven 的固定 [builder-9.1.1-sources.jar](https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/9.1.1/builder-9.1.1-sources.jar) 检查 `com/android/builder/internal/packaging/IncrementalPackager.java`：release 传 `Deflater.DEFAULT_COMPRESSION`，debug 传 `BEST_SPEED`。公开包装 DSL 提供是否压缩，没有压缩级别属性。不应依赖反射修改 AGP 内部 `ApkFlinger` 字段。

SDK 的官方 `zipalign -z` 可用 Zopfli 重新压缩已压缩的 ZIP 条目；未压缩条目继续执行对齐。这样可以保留每个条目的完整原始载荷，并且不需要更改 `omni.ja` 内部文件、uBO XPI 的内部签名或任何许可证。[官方命令文档](https://developer.android.com/tools/zipalign)、[AOSP ZipAlign.cpp](https://android.googlesource.com/platform/build/+/master/tools/zipalign/ZipAlign.cpp)。已轻量调用本机 Build Tools 37.0.0 的帮助输出，确认支持 `-z` 和 `-P 16`。该二进制不接受 `-h`，会返回非零状态并打印 usage；这不是重压缩测试。

应在设备测试结束后，单独执行外层重压缩实验。候选流程如下，尚未执行：

```text
unsigned ARM64 release APK
  → zipalign -z -P 16 4 input.apk aligned.apk
  → apksigner sign --out final.apk [同一发布签名配置] aligned.apk
  → zipalign -c -P 16 4 final.apk
  → apksigner verify --verbose --print-certs final.apk
```

重压缩必须在最终 `apksigner` 签名前进行。签名后的任何改动都会使原 APK 签名失效；如果实验输入来自当前已签 APK，其优化输出必须重新签名，再验证相同证书，不能直接交付。生产链优先生成未签中间产物，并将优化、对齐、签名纳入一个明确的 release 任务。[签名顺序](https://developer.android.com/tools/zipalign)、[apksigner](https://developer.android.com/tools/apksigner)。

验收优化产物必须同时验证：最终文件严格小于 110,000,000 字节；所有非签名条目的路径、原始长度、CRC 和 SHA-256 相同；无重复条目；ARM64 `.so`、DEX、应用资源、全部网页语言、安全 blocklist、原签名 XPI 和许可证完整；原 `omni.ja` 的 4079 + 5 项检查再次通过；对齐、APK 签名与证书通过；最终安装/启动与受控浏览、uBO、播放器等 release 冒烟通过。体积是否足够尚无测量结果。

## Zopfli 执行成本与增量路径

进一步只读检查 AOSP [`ZipAlign.cpp`](https://android.googlesource.com/platform/build/+/master/tools/zipalign/ZipAlign.cpp)、[`ZipFile.cpp`](https://android.googlesource.com/platform/build/+/refs/heads/main/tools/zipalign/ZipFile.cpp)、[Zopfli 默认值](https://android.googlesource.com/platform/external/zopfli/+/refs/heads/main/src/zopfli/util.c) 得到：

- `copyAndAlign` 串行遍历全部条目，对每个已压缩条目调用 `addRecompress`。没有条目筛选、线程数、迭代数或持久结果缓存的命令行开关；本机 SDK 37.0.0 usage 与该接口一致。
- `addRecompress` 先将一个条目完整解压到 `malloc(uncompressedLen)` 的缓冲区，再调用 `ZopfliDeflate`。此路径不是按输入流分块送给压缩器；`libxul.so` 单项输入缓冲区就约 153.8 MB，另有输出缓冲区与算法工作区。内部 master block 会限制部分算法工作集，不等于整个输入不驻留内存。
- `ZopfliInitOptions` 的默认 `numiterations=15`，开启 block splitting。工具没有覆盖该值的公开参数。[Zopfli 头文件](https://android.googlesource.com/platform/external/zopfli/+/refs/heads/main/src/zopfli/zopfli.h) 还提示数 MB 以上文件宜减少迭代，但不能把建议直接映射成 `zipalign` 参数。
- 此调用链没有条目级并行调度。链接依赖中的线程库不能证明实际会并行压缩；执行计划应按单个计算核心估算。Zopfli 的 longest-match cache 是本次算法内存缓存，不会让第二次独立 CLI 自动跳过同一个库。

全部条目原始长度合计为 233,237,356 字节；其中未压缩条目不会重新编码。没有本机吞吐实测，不能可靠承诺完成分钟数。规划时应把它当作分钟到数十分钟的独立 release 工作，并保留更长运行余量，而不是普通 zipalign 的秒级步骤。仅作换算示例：若真实吞吐分别为 0.1 / 0.5 / 1 MB/s，233 MB 分别约需 39 / 8 / 4 分钟；这些吞吐是示例假设，不是对本机或本 APK 的预测。运行后应记录同一进程的 CPU 时间、墙钟时间、峰值内存和最终长度，不能因一次观察超时就重启同一工作。

如果全量工作过长，SDK CLI 本身没有“仅重压缩大 `.so`”或缓存模式。一个不用修改 SDK/AGP 内部实现的可实现路径，是用 Android 官方开源 [Zipflinger 的 `ZipSource`](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/zipflinger/README.md) 将选定库按原压缩格式复制到小 ZIP，再只对这个 ZIP 执行 `zipalign -z`，最后用 `ZipSource` 不重编码地导入全新未签 APK，执行普通对齐和最终签名。应生成新归档而不是原地删除大库留下填充空洞。其余条目也从原包按原压缩格式复制，仍须执行上述完整性校验。

同一机制可缓存优化后的库条目；缓存键必须包括原始载荷 SHA-256、SDK/zipalign 二进制版本或 SHA、压缩选项、归档格式版本，并对缓存内容重新验 SHA。Gecko 原生库相同而应用代码改变时可复用，Gecko 升级会自然失效。这是待实现的构建逻辑，不是现有开箱即用的 `zipalign` 选项。是否值得先筛选 `libxul.so` 或全部 17 个库，须由第一次压缩收益决定，不能先保证能少 1.06 MB。

2026-10-24 后续新增独立 [`tools/apk-release-buildlogic`](../tools/apk-release-buildlogic/README.md)，尚未接入全局 Gradle。它使用固定官方 Zipflinger 9.1.1 的 `ZipSource.COMPRESSION_NO_CHANGE`，从一个**已完成且完整 SHA 固定**的优化缓存 APK 复用同路径、实际原始 SHA/len/CRC/method 与文件元属性均相同且压缩后更小的条目。发生变化的 DEX/JS 等直接使用当前 AGP APK 的压缩字节；缓存额外文件不会混入。全量写入新未签归档后再次独立验证路径集合、原始载荷和所选来源的压缩字节，并去除原应用签名文件与签名块。已用小型 fixture 跑过 74 项检查，包括相同 len/CRC 但 SHA 不同的替换攻击、重复项、损坏缓存、路径遍历、许可证保留和签名移除；独立重编后也再次 74 项通过。日志为 `artifacts/apk-size-reuse/run-20261024-073531-8c4c77de/tests.log` 与 `run-20261024-074509-646d4682/tests.log`。

## 已完成的真实中间产物实验

独立 SDK whole-APK Zopfli 进程正常退出，耗时 **854.184 秒**（约 14 分 14 秒），产出 107,570,406 字节的未签 APK，SHA-256 为 `204ad07b3fd782a1c0324567dae33c13e455a74176b9fd2838ae056a55702bcc`。与固定原包的全部 **1516** 个非签名 payload 逐项比较通过，普通 SDK 对齐检查退出码为 0。证据：`artifacts/apk-size/zopfli-result.json`、`zopfli-payload-verification.json`。

在该进程完成并固定 SHA 后，helper 实际复用 1220 项、从当前 APK 复制 296 项，生成 107,569,934 字节的未签 APK，SHA-256 为 `74fbe1a46fbed1db57b1697867aa0d14f9303de4af3ec19a9ab4bd9594216f64`。独立 Python 再次证明全部 1516 个 payload 的路径、长度、CRC、SHA 和压缩方式与当前 APK 相同。证据：`artifacts/apk-size/reuse-original-report.json`、`reuse-payload-verification.json`。**这些是未签中间产物，不能替代最终签名文件大小、证书或运行验收。**

新增 [`tools/optimize-release.ps1`](../tools/optimize-release.ps1) 将已签 AGP 原包/完整 SHA、已完成缓存/完整 SHA、固定官方 Jar、JDK、SDK Build Tools、Python、keystore/alias 与两个密码环境变量名作为显式输入，串联 helper 编译/复用、普通对齐、SDK 签名、最终对齐/签名与原证书集合一致性、独立 Python 严格 `<110,000,000` 逐 payload 校验；全部通过且输入 SHA 仍匹配后才无覆盖发布新 output。脚本只传 `env:NAME` 给 SDK signer，不读取/打印密码值，不接全局 Gradle。日志和 JSON 留在 output 旁，无递归删除。19 项预检/拒绝路径检查通过，证据为 `artifacts/apk-release-script-tests/run-20261024-075609-b170a586/tests.log`；该串联脚本尚未执行真实签名。

## 后续可选路径

最新完整源码也已重建并通过同一串联：0.5.1/code9原AGP APK为111,075,393字节
（SHA `7db5436c71bb90a4ee943ef15dc5fece08101118d71317626067561b1210a39a`），
最终 `artifacts/releases/Miru-0.5.1-arm64.apk` **107,892,912字节**，SHA
`2364c2ad74bc46bbd9d42738e2f631daca588f47e4147fa4103c13ceaaf91362`。
本次1517 payload全部保留，SDK对齐/签名/原证书集合一致，Gecko原4079项与5项新增另验通过。
证据为最终文件同名 `.verification-20261024-084733-8563a5f9/` 与
`artifacts/browser-core/core-051-final-omni-verification.json`；最新源码的单ARM64体积门已通过。
签名仍为本机Android Debug，不代表正式发布或ARM64真机运行已验收。


2026-10-24 完整串联已在保留的旧 browser-core 原包上实际通过：最终已签 APK 为
`artifacts/apk-size/Miru-0.5.1-pipeline-proof-arm64.apk`，**107,696,213 字节**，
SHA-256 `ace087341183ea48abd826f199d0c6111fc86faff50f2b6eefd78fdc40467c48`。
SDK 对齐、签名和原 Android Debug 证书一致性通过，独立 Python 证明全部 1516 payload 保留且严格小于
110,000,000 字节；日志为同名 `.verification-20261024-082012-1fea0657/pipeline.json`。
首次实跑发现 SDK37 的 `V2 Signer:` 格式与旧正则不兼容，已抽取证书解析器并用真实输出等 18 项检查验证；
随后修复 JSON 中大小写不同资源路径被 PowerShell 默认对象解析拒绝的问题，脚本明确要求 PowerShell7。
这证明固定旧输入的打包工具链，不证明最新源码产物或 ARM64 真机运行，也不替代正式发布密钥。


官方 bundletool 1.18.3 的 [`BundleConfig.Compression.apk_compression_algorithm=P7ZIP`](https://github.com/google/bundletool/blob/1.18.3/src/main/proto/config.proto) 也是受支持的 DEFLATE 编码选择，其注释说明较小 APK 可能换来较大更新补丁。若采用，需保留完整 AAB 配置和签名链、验证真实安装 APK 集；不能把 AAB 文件长度、下载估算、单个配置 split 或 MiB 数字当作当前单 ARM64 APK 门槛通过证据。

不通过删除功能、网页语言、安全资源、uBO、许可证或更换引擎版本来凑体积。不要压缩 `resources.arsc`、盲目启用旧系统不兼容的稀疏资源表，或将大库改成安装后联网下载；这些路径不等价于现有规格交付物。

## 2026-10-26：0.5.6上传首批开发包

原AGP ARM64 release为111,158,173字节，SHA `212aca35e829fe398fa78b0930c33e287d67672809f81aada1d8a36ee5ecd0a4`。
同一工具链最终输出 `artifacts/releases/Miru-0.5.6-arm64.apk` **108,007,656字节**，SHA
`f874d3192cf7bc057eabd58b259b0d296d51b3e738549280c5ba836b98023dbb`。
1518个payload、SDK对齐、原证书一致与17个ELF64/AArch64库均通过；Gecko为4077原项不变、两项精确GEO/导航修改、5项官方新增。
完整流水线见同名 `.verification-20261026-093251-4dcff025/`，独立Gecko和ELF记录见
`artifacts/browser-core/056-release-omni-verification.json`、`056-release-native-verification.json`。
仍为Android Debug签名；R8运行、ARM64运行与正式发行继续待验，详见[B13上传记录](B13-FILE-UPLOAD-VALIDATION.md)。

## 2026-10-27：0.5.7启动修复开发包

最终`artifacts/releases/Miru-0.5.7-arm64.apk`为**108,011,752字节**，SHA
`74e95bc52ef4bf7186f2a852ca1017392a5c36aff21060ddec714d74816d867e`。
同名`.verification-20261027-061211-10e647bb/pipeline.json`记录全部步骤成功，1518payload完整、对齐及同Debug证书；
`057-release-native-verification.json`验证17个ELF64/AArch64库，`057-release-omni-verification.json`验证4077+2+5资源。
打包范围与运行门分开，详见[0.5.7记录](M1-057-STARTUP-VALIDATION.md)。

## 2026-10-28：0.5.8拍摄上传开发包

`dbddaed`的R8/shrink ARM64构建成功（2分50秒）；原AGP包111,164,037字节，SHA
`2ca8b4ba73bf3513388933e5bb67750e5ea4849fd3610816e7845f2fc59eac24`。
最终`artifacts/releases/Miru-0.5.8-arm64.apk`为**108,011,752字节**，SHA
`bf92e7a4f4b69893f25632e4057b5d80f7777a27f3fbcc27a58a541c1ecd062c`。
同名`.verification-20261027-215748-0e1e30ab/pipeline.json`证明1518payload完整、对齐和同Debug证书；
`058-release-native-verification.json`与`058-release-omni-verification.json`分别验证17个ARM64库和4077+2+5资源。
时间戳目录为UTC，本节日期为Asia/Shanghai。正式签名、R8/ARM64运行及完整发行门仍待，
详见[拍摄上传记录](B13-CAPTURE-VALIDATION.md)。

## 2026-10-30：0.5.9目录与录音开发包

`15c2943`的R8/shrink ARM64构建成功（6分31秒）。原AGP包111,174,025字节，SHA
`6f8d7fa55df99ea48fd414090d41d1233fa8955d4fca57e261dc6fa24e4c7efc`。
最终`artifacts/releases/Miru-0.5.9-arm64.apk` **108,024,040字节**，SHA
`2f691037ce171e9edd7df915979fc45a2915c6d587f467d73e31c06f8598265d`。
同名`.verification-20261029-182614-f33e5209/pipeline.json`记录1518payload完整、对齐、签名及同Debug证书；
`059-release-native-verification.json`与`059-release-omni-verification.json`另证17个ARM64库和4077+2+5资源。
正式签名、R8/ARM64运行和物理性能仍未验收，详见[本版记录](B13-AUDIO-CAPTURE-VALIDATION.md)。
