# Miru Chromium 内核构建套件（159.0.8154.33 · Linux x86_64 / WSL2）

本目录负责在 **Linux x86_64 构建机、CI 或本机 WSL2（`Ubuntu-24.04`）** 中编译去 Google 化 Chromium `159.0.8154.33`（`//miru_engine:miru_chromium_engine_aar`），动态链接统一 Rust 原生核心 `libmiru_core.so`（`DT_SONAME: libmiru_core.so`，与 `libmiru_chromium.so` 共同满足 Android 15 16 KB ELF 页对齐），并将预构建 `.aar` 发布到本地 Maven 仓库 `tools/local-chromium-repo/` 供 Windows Gradle 直接消费。

## 硬件与环境要求

- **操作系统**：Ubuntu 22.04 / 24.04 LTS x86_64 或 WSL2 `Ubuntu-24.04`（源码盘需置于 `ext4` 以避免 NTFS IO 瓶颈与权限异常）
- **内存**：≥ 64 GB（推荐 64–128 GB）
- **磁盘**：≥ 250 GB 可用 NVMe / `ext4` 空间
- **工具链**：`depot_tools`、Clang/Chromium Android SYSROOT、`rustup`（`aarch64-linux-android` + `x86_64-linux-android`）、Android NDK 28.2+（Windows 侧通过 `tools/build-rust-android.ps1` 编译并输出至 `native/android-jniLibs/`）

## 构建与消费流程

1. **生成统一 Rust 共享库**：在 Windows 运行 `tools/build-rust-android.ps1`，产出带 `DT_SONAME: libmiru_core.so` 的双架构 `native/android-jniLibs/{arm64-v8a,x86_64}/libmiru_core.so`（避免静态链接 `libmiru_core.a` 时 `mold` 与 Chromium 内部 Rust `libstd_std.rlib` 在 `rust_eh_personality` 上产生符号冲突，同时 APK 内仍只打包一份 Rust 运行时）。
2. **同步并编译 Chromium AAR**：在 Linux / WSL2 运行：
   ```bash
   bash tools/chromium-build/build-miru-chromium.sh dual 159.0.8154.33
   ```
   - `sync-miru-engine.sh` 将 `tools/chromium-build/miru_engine/`、`native/miru-core/include/miru_core.h` 与 `libmiru_core.so` 同步至 `$CHROMIUM_SRC/miru_engine` 并挂载 `//:miru_all`。
   - `autoninja` 编出 `arm64-v8a` / `x86_64` 的 `miru_chromium_engine_<abi>.aar`、`miru_chromium.pak`、`icudtl.dat` 与 `snapshot_blob.bin`。
   - `package-aar.py` 合并双 ABI `.so`、合入 `org/jni_zero/GEN_JNI.class`、修复合并后的 `R.txt` 与 XML `attr` 重复定义、剔除冗余依赖类与 `play-services-*.properties` 元数据、以零压缩方式写入 Chromium 启动必需资产，并发布带 `SHA256SUMS` 的 `tools/local-chromium-repo/app/miru/miru-chromium-engine/159.0.8154.33/`。
3. **Windows 日常 Gradle 构建**：
   - Chromium 变体（默认开发/发布验证目标）：`:app:assembleChromiumDebug` / `:app:assembleChromiumRelease`（单 ABI `arm64-v8a`）
   - 冻结 Gecko 基线变体：`:app:assembleGeckoDebug`
   - 日常改动 Compose UI、下载、播放器、超分或 `:browser:engine-chromium` 时无需在 Windows 重编 Chromium C++。

## 文件说明

- `miru_engine/`：Miru 注入 Chromium 的最小 C++ / Java / GN 嵌入层（`MiruMainDelegate`、`MiruContentBrowserClient`、`MiruTab`、`MiruURLLoaderFactory` + `MiruUrlLoaderThrottle` 等），不含 GMS / Sync / SafeBrowsing 遥测。
- `args-arm64.gn` / `args-x64.gn`：实测通过的去 Google 化 GN 编译参数（`optimize_for_size=true`、`symbol_level=0`、禁用 PDF/Print Preview/Remoting/SafeBrowsing/Reporting/RLZ、启用 `proprietary_codecs` + `icu_use_data_file`）。
- `sync-miru-engine.sh`：同步 `//miru_engine` 源码、C 头文件及双架构 `libmiru_core.so` 到 Chromium 源码树。
- `build-miru-chromium.sh`：统一触发同步、GN、Autoninja 与 `package-aar.py` 的可复现构建入口。
- `build-x64-incremental.sh`：WSL2 下仅迭代 `miru_engine` C++/Java 并增量重编 `x86_64` 模拟器 AAR 的快捷脚本。
- `package-aar.py`：打包多 ABI AAR 并写入非压缩资源、POM 与 `SHA256SUMS` 到 `tools/local-chromium-repo/`。
