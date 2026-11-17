# 第三组日常 P1 检查点（0.5.23）

检查点使用 `4a16a1b` 的 0.5.23/code31 源码。固定 Debug 与 ARM64 Release 的 741 项源码输入一致，构建前后无漂移。本站数据清理在 `codex/site-data-wip`，P11 和 P2 仍按维护者方向冻结。

## 构建和 ARM64 包

- 固定 Debug 构建 `daily-p1-checkpoint-debug-01` 通过。主 APK SHA-256 为 `90201c9c44a4c94983d4e792d8a6bebdc7a6d8002589399d4dc364a99a9fb43f`，测试 APK 为 `4c017ee83cac84f87afb80f351372834fd4bb024eb5f16fb19d3fdc453bf0047`。Debug 和 Release Lint 均为 0 错误、29 警告。
- Release 第一次停在 R8/Lint 附近，未生成终态和固定 APK。保留 `daily-p1-checkpoint-release-01` 的日志。`daily-p1-checkpoint-release-02` 重新校对同一份源码，R8、资源缩减、Lint 和全模块 JVM 成功。报告共 300 项，299 通过、1 项 Windows 符号链接测试跳过，零失败。原始 R8 包 111,867,767 字节，SHA-256 为 `5f23f450dd1dde74ac6fd814e0c321f322f45e33315cbfa374b510dc8f590557`。
- 开发包 `artifacts/releases/Miru-0.5.23-arm64.apk` 为 108,743,102 字节，SHA-256 为 `ffb1775da836ecd5ae375de6f9aaee9ba6a100003cb846f582f065c792637bdd`。优化时复用 0.5.22 的压缩内容，独立核对 1,624 项非签名内容的路径、长度、CRC、SHA-256 和压缩方法。通过 16 KB 对齐、同一 Android Debug 证书、17 个 ELF64/AArch64 库，以及 Gecko 的 4,077 项原资源、两项补丁和五项增补验证。记录见 `artifacts/releases/Miru-0.5.23-arm64.apk.verification-20261117-065029-d4ee288b/`。本版正式签名及 ARM64 真机运行仍待验证。

## 第三组功能证据

- 后台播放覆盖系统媒体通知、退到后台仅播放音轨、暂停恢复和无痕行为。手势批次验证了缩放、平移、吸附、临时倍速档位及松手恢复。[后台播放](BACKGROUND-PLAYBACK-VALIDATION.md) · [双指缩放](PLAYER-ZOOM-VALIDATION.md) · [长按换档](PLAYER-HOLD-SPEED-VALIDATION.md)
- 片头片尾按站点和路径保存，覆盖同目录复用、相似路径、主动拖进片尾及无痕隔离。[片头片尾](PLAYBACK-SKIPS-VALIDATION.md)
- 安全设置测试实际 HTTPS 升级、第三方 Cookie 请求和自定义 DoH 到达指定端点的 TLS 请求。DoH 正常 DNS 查询与应答仍待验证。[浏览器安全设置](BROWSER-SECURITY-VALIDATION.md)
- 书签从旧版 SQLite 表迁移后保留书签和历史，在 App 内验证子文件夹及搜索。存储条用真实普通文件、稀疏暂存和离线视频核对使用量、可访问性文字和删除更新。[书签文件夹](BOOKMARK-FOLDERS-VALIDATION.md) · [下载存储](DOWNLOAD-STORAGE-VALIDATION.md)

## 设备回归

使用 `emulator-5554`、API 35、x86_64、SwiftShader、只读启动的 `WfmAdApi35`。常规命令按仓库既有配置排除 `DownloadRestartTest`、`OfflineRestartTest`、`RuntimeSitePermissionIntegrationTest`、`AudioCaptureIntegrationTest` 和 `app.miru.browser.capability` 包。201 项是此范围的预期用例数。主包和测试包均从固定构建安装，并核对安装后的大小和 SHA-256。

首次运行 `daily-p1-checkpoint-routine-01` 的宿主机输出停在第 9 项，缺少终态结果文件。独立设备日志仍记录了最终 201 项、111 项失败、0 跳过，其中大量用例无法再使用系统 UI 自动化连接，出现 `DeadObjectException`、输入注入失败、无法截图和无法读取无障碍节点。保留设备日志与早期测试断言，整轮不通过。恢复后保留同一输入，重启模拟器并重新运行 `daily-p1-checkpoint-routine-02`。两轮首装 uBlock 实际拦截预检各 1/1 通过。

第二轮 **201/201 通过，零失败、零跳过**。JUnit 用时 1,136.611 秒。浏览器核心 7/7、图片复制策略 9/9、Cookie 桥接 3/3。系统文件上传、历史图标、网页视频交接、超分及第三组新增的 7 项常规用例均通过。安装身份、命令和结果在 `artifacts/m3-cached-ranges/daily-p1-checkpoint-routine-02/`。首次运行的独立日志、复跑设备环境、持续日志和测试截图分别在 `artifacts/daily-p1-checkpoint/routine-live.log` 及 `artifacts/daily-p1-checkpoint/routine-recovery-01/`。

同一固定包补跑常规套件外的第三组专项。`daily-p1-checkpoint-background-01` 的后台播放 3/3 通过，使用未授予通知权限的临时用户观察实际系统媒体会话。`daily-p1-checkpoint-capability-01` 的片头片尾、安全设置和下载存储合计 6/6 通过，采用另一临时用户。APK 安装校验全部通过，临时拉取文件和两名测试用户已移除，恢复原用户。两个强停重启类、独立系统权限和录音类沿用各自已有范围。真机日常冒烟及公网网站矩阵仍待。
