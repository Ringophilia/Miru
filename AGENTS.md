# Workspace instructions

## 当前阶段方向（维护者 2026-11-25 调整：Chromium 路线）

本节优先于 `docs/ACCEPTANCE.md`「下一阶段顺序」和 `docs/PROGRESS.md` 中的任何历史排序。`MIRU-SPEC.md`（v2.0）与 `docs/CHROMIUM-MIGRATION-PLAN.md` 是当前需求与迁移路线来源，本节规定**先做什么、做到什么程度**。修改本节须经维护者同意。

### 目标

将 Miru 引擎路线切换为**去 Google 化自编 Chromium（Cromite 思路）+ 原生 `adblock-rust` 广告拦截 + C++ 网络层视频嗅探**，完整复用现有的 Compose 浏览器外壳、本地 SQLite 历史/书签、OkHttp 下载引擎、Media3 播放器、离线缓存与超分辨率模块，做出一个能在真手机上日常主力使用的版本。

### 冻结

- **GeckoView 主线冻结于 `0.5.24`。** 现有 GeckoView / Android Components / uBlock Origin 代码保留为解耦期间的可运行参考基线，不再向其追加新功能、规则适配或 uBO 专项排查；唯一允许的改动是在 Phase C0 引擎解耦期间保持其可编译且不引入崩溃回归。
- **P11 磁盘缓存区段继续冻结。** 未完成改动保留在分支 `wip/p11-cache-ranges`；已在 `main` 上的提交保持不动，除非修复崩溃或播放错误。
- **P2 项（B22–B24、P23）继续冻结。**

### 优先级（按阶段顺序推进，阶段门见 `docs/CHROMIUM-MIGRATION-PLAN.md`）

1. **Phase C0（Windows 本地即可推进，App 保持可编译运行）**：抽出纯 Kotlin `:browser:engine-api`，将现有 Gecko/AC 收拢至 `:browser:engine-gecko`；解除 `:browser:toolbar` 与 `:feature:*` 对 Gecko/AC/uBO/`miru-bridge` 的直接依赖；完成 `adblock-rust` 规则引擎原型与 JVM 验证。
2. **Phase C1（前提：Linux x86_64 构建机 / CI 就绪，≥ 64 GB 内存、≥ 250 GB SSD）**：搭建去 Google 化 Chromium + Cromite 可复现构建流水线，产出版本化、SHA-256 固定的预构建 `.aar`（ARM64 必选、x86_64 可选）供 Windows Gradle 消费；跑通 `:browser:engine-chromium` 最小浏览壳（多标签、无痕 `OTRProfile`、`BrowserControls` 滚动工具栏、下拉刷新、会话恢复、历史书签对接）。
3. **Phase C2（核心能力替换）**：在 Chromium C++ 网络层（`URLLoaderThrottle` / `NavigationThrottle`）挂载原生 `adblock-rust`（含外观隐藏、脚本注入、原生盾牌面板与元素拾取），并落地 C++ 网络层视频嗅探 + 轻量页面脚本，直连现有 `:feature:media-detect` 与 `:feature:player`。
4. **Phase C3（功能对齐与隐私安全闭环）**：对接 Chromium 下载/Cookie 桥、B13 全部权限与网页提示、DoH / 仅 HTTPS / 阻止第三方 Cookie、网页缩放、数据清理与无痕通知；达成对等后归档/移除 `:browser:engine-gecko`。
5. **Phase C4（真机验收与发布）**：ARM64 真机日常与性能冒烟、单 ABI 发布包体积门（`< 150 MB`）、上游 Chromium 安全更新 rebase 流程、第三方分发许可审查与正式签名。

### 验证节奏

- **每个功能批次**：编译、相关模块 JVM 测试、只针对改动范围的专项测试。不跑完整常规回归，不打 ARM64 包。
- **阶段门检查点**（完成上面一个 Phase，或维护者要求时）才做：完整常规回归、ARM64 打包验证、更新 `ACCEPTANCE.md`、bump `versionName`。不要每批都升版本号。
- 测试规模与功能相称：优先 JVM 单测；单个设备测试类超过约 400 行时先检查是否过度。不为“证明”而构造字节级 oracle，除非在修一个具体 bug。
- **诚实规则不变**：未运行、被排除或失败的测试不写成通过；**已冻结的 GeckoView `0.5.24` 历史证据（`E0`–`E43`）绝不计作 Chromium 内核的验收通过**，依赖引擎的验收门必须在 Chromium 上重新验证。

### 时间盒

同一个功能或迁移步骤连续 3 个提交或 1 天仍没有**可验证的阶段进展**，就停下：在 `docs/STATUS.md` 写清卡在哪里、可选方案，然后问维护者，不要继续深挖。

### 真机

`adb devices` 出现非模拟器设备时，优先做真机冒烟：安装、浏览常用网站（含登录）、验证广告拦截、播放网页视频、下载文件，发现的问题记入 `docs/STATUS.md` 并排到当前阶段最前。没有真机不阻塞 Phase C0 解耦与本地开发，但不能把模拟器结果说成真机结果。

### 汇报

- 每批结束更新 `docs/STATUS.md`：中文大白话，维护者不看代码也能读懂；不堆提交哈希、测试编号和证据代号。
- 会话结束时用 3–5 行告诉维护者：这批做了什么、当前能看到什么变化、下一步做什么。

## Validation artifacts and disk use

- Use `tools/build-player-validation.ps1` for frozen Debug validation builds and `tools/run-device-validation.ps1` for device regressions. Set `JAVA_HOME` and `ANDROID_HOME` or supply the runner's `-Adb` argument for this machine.
- Install directly from the pinned build snapshot. Never copy APKs into each test run directory. Pull installed APKs only to a temporary file, compare SHA-256 and size, and delete the temporary file in `finally`.
- Keep the latest two successful Debug snapshots, latest two ARM64 release APKs, and the latest published release's stock R8 input. Protect live validation inputs with the process/start-time lease used by the scripts. Supply `-Keep` for an additional input that is actively needed.
- After each build/validation batch and release publication, run `tools/clean-validation-artifacts.ps1 -Apply`. It must stay inside this repository's `artifacts` directory, reject reparse paths, and protect tracked files. Do not clean another checkout, shared SDK, Gradle dependency cache, source, or test fixture.
- Keep reports, logs, screenshots, source manifests and hashes as evidence. Historical report paths can refer to APKs subsequently pruned; consult the cleanup ledgers rather than claiming those binaries are still present.
- Aim to keep `artifacts` below 3 GB with current package sizes. If usage grows, inspect it and remove superseded generated binaries using the retention script. Do not accumulate full APKs merely to preserve evidence.
- Release optimization removes generated unsigned/signed scratch in `finally`, including on failure. Use `-KeepScratch` only for a specific investigation and prune it when finished.

See [docs/VALIDATION-ARTIFACTS.md](docs/VALIDATION-ARTIFACTS.md) for commands and retention details.
