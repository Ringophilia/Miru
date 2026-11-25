# Miru Chromium 内核构建套件（Phase C1 前提：Linux x86_64）

本目录为 Phase C1 起在 **Linux x86_64 构建机或 CI** 上编译去 Google 化自编 Chromium（Cromite 思路）并静态链接统一 Rust 核心 `libmiru_core.a` 提供可复现入口。

## 硬件与系统要求

- **操作系统**：Ubuntu 22.04 LTS 或 24.04 LTS（x86_64）
- **内存**：≥ 64 GB（推荐 128 GB，用于 ThinLTO 链接）
- **磁盘**：≥ 250 GB 可用 NVMe 固态硬盘空间
- **工具链**：`depot_tools`、`rustup`（已安装 `aarch64-linux-android` 与可选 `x86_64-linux-android`）、Android NDK r27+

## 制品契约（供 Windows 日常 Gradle 消费）

1. 在 Linux 上运行 `tools/chromium-build/build-miru-chromium.sh arm64-v8a`（可选 `x86_64` 供模拟器 CI）。
2. 输出版本化预构建 `.aar`（内含去 Google 化 Chromium 原生库 + 静态链接的 `libmiru_core.a` + Java/JNI 嵌入桥接）及对应 `SHA256SUMS` 与补丁清单。
3. Windows 日常 Gradle 工程直接消费固定版本号与 SHA-256 的预构建 `.aar`，不在 Windows 本地重编 Chromium C++。

## 文件说明

- `args-arm64.gn`：去 Google 化（禁用 GMS、Sync、UMA、Crashpad 上传、RLZ、SafeBrowsing 远端上报）与体积裁剪（禁用 PDF/打印/NaCl/VR、ThinLTO、`symbol_level=0`）的 GN 编译参数模板。
- `build-miru-chromium.sh`：先交叉编译 `native/miru-core`（`libmiru_core.a` / `.so`），再驱动 `gn gen` + `autoninja` 的统一构建入口脚本。
