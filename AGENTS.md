# Workspace instructions

## 当前阶段方向（维护者 2026-11-03 设定）

本节优先于 `docs/ACCEPTANCE.md`「下一阶段顺序」和 `docs/PROGRESS.md` 中的任何排序。`MIRU-SPEC.md` 仍是需求来源，本节只规定**先做什么、做到什么程度**。修改本节须经维护者同意。

### 目标

先做出一个**能在真手机上日常使用**的版本，再追求验收证据完备。用户每天会碰到的缺口，优先于任何单项功能的深度打磨。

### 冻结

- **P11 磁盘缓存区段冻结。** 不再扩展 MP4 / AVC / B 帧 / edit-list 等区段映射、字节级 oracle 或相关 fixture。工作区里尚未提交的 P11 改动提交到新分支 `wip/p11-cache-ranges` 保存（不要丢弃），然后切回 `main` 继续；已在 `main` 上的 P11 提交保留不动。唯一例外：修复已合入代码造成的崩溃或播放错误。
- P2 项（B22–B24、P23）不开始。

### 优先级（按顺序做，一组做完再进下一组）

1. **日常 P0 缺口**：地址栏在线搜索建议 + 内联域名补全（B3）；网站图标 favicon（替换首字母色块）；六个主题色预设（B19）；关闭无痕标签的常驻通知（B9）；首次启动设为默认浏览器的可跳过引导（B14）。
2. **收尾进行中的 P0**：B13 剩余权限 / 网页提示，只做到功能闭合；uBO 首次安装超时的根因（A1 / A6）。
3. **高感知 P1**：后台播放（P18）、双指缩放（P22）、长按后滑动换档（P3）、跳过片头片尾（P20）、HTTPS-only / DoH / 第三方 Cookie 设置（B21）、书签文件夹（B8）、下载存储条（D7）。
4. **打磨**：§7 动效、§5.11 设置补全、无障碍。

### 验证节奏

- **每个功能批次**：编译、相关模块 JVM 测试、只针对改动功能的设备测试类。不跑完整常规回归，不打 ARM64 包。
- **检查点**（完成上面一组，或维护者要求时）才做：完整常规回归、ARM64 打包验证、更新 `ACCEPTANCE.md`、bump `versionName`。不要每批都升版本号。
- 测试规模与功能相称：优先 JVM 单测；单个设备测试类超过约 400 行时先检查是否过度。不为“证明”而构造字节级 oracle，除非在修一个具体 bug。
- 诚实规则不变：未运行、被排除或失败的测试不写成通过。

### 时间盒

同一个功能连续 3 个提交或 1 天仍没有**用户在 App 里能看到的进展**，就停下：在 `docs/STATUS.md` 写清卡在哪里、可选方案，然后问维护者，不要继续深挖。

### 真机

`adb devices` 出现非模拟器设备时，优先做真机冒烟：安装、浏览常用网站（含登录）、播放网页视频、下载文件，发现的问题记入 `docs/STATUS.md` 并排到当前组最前。没有真机不阻塞功能开发，但不能把模拟器结果说成真机结果。

### 汇报

- 每批结束更新 `docs/STATUS.md`：中文大白话，维护者不看代码也能读懂；不堆提交哈希、测试编号和证据代号。
- 会话结束时用 3–5 行告诉维护者：这批做了什么、在 App 里能看到什么变化、下一步做什么。

## Validation artifacts and disk use

- Use `tools/build-player-validation.ps1` for frozen Debug validation builds and `tools/run-device-validation.ps1` for device regressions. Set `JAVA_HOME` and `ANDROID_HOME` or supply the runner's `-Adb` argument for this machine.
- Install directly from the pinned build snapshot. Never copy APKs into each test run directory. Pull installed APKs only to a temporary file, compare SHA-256 and size, and delete the temporary file in `finally`.
- Keep the latest two successful Debug snapshots, latest two ARM64 release APKs, and the latest published release's stock R8 input. Protect live validation inputs with the process/start-time lease used by the scripts. Supply `-Keep` for an additional input that is actively needed.
- After each build/validation batch and release publication, run `tools/clean-validation-artifacts.ps1 -Apply`. It must stay inside this repository's `artifacts` directory, reject reparse paths, and protect tracked files. Do not clean another checkout, shared SDK, Gradle dependency cache, source, or test fixture.
- Keep reports, logs, screenshots, source manifests and hashes as evidence. Historical report paths can refer to APKs subsequently pruned; consult the cleanup ledgers rather than claiming those binaries are still present.
- Aim to keep `artifacts` below 3 GB with current package sizes. If usage grows, inspect it and remove superseded generated binaries using the retention script. Do not accumulate full APKs merely to preserve evidence.
- Release optimization removes generated unsigned/signed scratch in `finally`, including on failure. Use `-KeepScratch` only for a specific investigation and prune it when finished.

See [docs/VALIDATION-ARTIFACTS.md](docs/VALIDATION-ARTIFACTS.md) for commands and retention details.
