# ARM64 APK 体积研究（待执行优化）

2026-10-24。此文是打包路径研究，不是 PERF08 验收通过记录；本轮未构建、重压缩、重签或操作设备。

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

## 后续可选路径

官方 bundletool 1.18.3 的 [`BundleConfig.Compression.apk_compression_algorithm=P7ZIP`](https://github.com/google/bundletool/blob/1.18.3/src/main/proto/config.proto) 也是受支持的 DEFLATE 编码选择，其注释说明较小 APK 可能换来较大更新补丁。若采用，需保留完整 AAB 配置和签名链、验证真实安装 APK 集；不能把 AAB 文件长度、下载估算、单个配置 split 或 MiB 数字当作当前单 ARM64 APK 门槛通过证据。

不通过删除功能、网页语言、安全资源、uBO、许可证或更换引擎版本来凑体积。不要压缩 `resources.arsc`、盲目启用旧系统不兼容的稀疏资源表，或将大库改成安装后联网下载；这些路径不等价于现有规格交付物。
