# B13 目录元数据与生命周期验证（0.5.10/code18）

本批在 0.5.9 目录首批之上增加 prepared manifest，保留源 MIME、mtime 和每份重复逻辑路径。
仍通过实际 DocumentsUI TREE 和 Gecko 第二 FolderUploadPrompt 授权；普通单/多文件与 capture
沿用既有路径。实现合同见 [B13-PREPARED-FOLDER-CONTRACT.md](B13-PREPARED-FOLDER-CONTRACT.md)。
本记录不代表完整 B13、完整常规回归或正式发行通过。

## 固定输入与构建

- 主 APK：`artifacts/folder-upload/0510-candidate-20261030-021842/Miru-0.5.10-debug.apk`，
  474,055,271 字节，SHA-256 `98784506205ec6198552fe9115f4396987ac34180abb8c3bab6ca7972b6964d1`。
- 首批 test：8,586,154 字节，SHA `15efd1bbfe01724548feb35a96a2f5895c704ca4edf8c3d664bb8ad3428d3fe4`。
- 符号链接/触摸就绪 test：8,591,083 字节，SHA `9a3a0728b9421aedd1bafc0a144e6157dc67e196b25c2e18128ca473afec0a85`。
- 授权窗口过渡驱动 test：8,591,119 字节，SHA `0489adc407a65bb3e51b00626fa857947c2720755cc22fcfa8625b53192ca025`，
  不可变 APK、源清单和 lint 在 `artifacts/folder-upload/0510-grant-transition-candidate-20261030-1347/`。
  两次测试增量构建均核对主 APK 未变，构建前后源清单无变化。
- `0510-build-20261030-021842.log`：主/测试构建与 JVM/lint 成功，5 分 14 秒。
  engine 共 104 项，103 通过、1 跳过、0 失败/错误；树复制 16/16、Prepared 7 通过/1 跳过。
  跳过原因是 Windows 无法创建实际符号链接，不计通过。lint 0 错误/22 警告。
- `0510-filesystem-candidate-20261030-030052633/build.log`：测试增量构建与 lint 成功，54 秒。
  后续 `0510-grant-transition-candidate-20261030-1347/build.log` 成功，1 分 10 秒，lint 仍为 0/22。
- 保留生产 helper 重名导致的首次编译失败 `0510-build-20261030-021551.log`，以及新增 Java
  checked-exception 捕获导致的编译失败 `0510-filesystem-candidate-20261030-025744531/`。
  Java 改为捕获 Exception 后断言实际 IOException，没有将任意异常视为预期成功。

## 真实设备逐轮记录

均为受控 API35/x86_64 `WfmAdApi35`、只读无快照实例，无 `pm grant`、伪造 ActivityResult、
PromptRequest 或 FileList。主/测试安装后再核对已安装 base APK 的 SHA。设备进程不存在后才创建
后续实例；此事实不作为下载重启或进程死亡验收证据。

| 输入 / 日志目录 | 执行 | 结果与边界 |
| --- | --- | --- |
| `0510-device-20261030-0255/metadata-first-*`，test 15efd | metadata 正向 1 项 | 1/1，23.148 秒 |
| 同目录 `folder-metadata-lifecycle-complete-*`，test 15efd | metadata2 + folder8，单次 10 项 | 9/10，105.094 秒；virtual 负例在 Inspect 后 30 秒未收到 report |
| 同目录 `virtual-negative-observation-*`，同 test | 原负例隔离 1 项 | 1/1，13.022 秒；不抵消上轮整组失败，未唯一证明首次失报根因 |
| 同目录 `filesystem-folder-ready-complete-*`，test 9a3a | filesystem3 + metadata2 + folder8，单次 13 项 | 12/13，70.64 秒；virtual 负例及链接均通过，late TREE 下一 owner 的第二次系统 grant 观察失败 |
| `0510-device-20261030-1348/folder-filesystem-complete-*`，test 0489 | 同 13 项，真实授权窗口过渡驱动 | 12/13，95.756 秒；late TREE 通过，virtual 负例在 page() 的网页准备阶段超时，尚未触发 TREE |

同一主/test 包随后执行 `0510-device-20261030-1348/routine-complete-*` 常规完整回归，
**130/130 通过，734.586 秒**，无失败/跳过。`routine-complete-tests-v2.json` 按原始终态逐项
核对：文件18、目录8、metadata2、Android文件系统3、benchmark2、GL3均包含在同次执行中；
不是将独立轮次拼接。v1逐类收集的 OrderedDictionary 分组错误原样保留，v2修正为真实JSON行
分组，原设备日志及130项全绿终态不受收集错误影响。排除 DownloadRestartTest、
OfflineRestartTest、RuntimeSitePermissionIntegrationTest、AudioCaptureIntegrationTest 四个
需要独立前置的类；排除不记通过。完整运行期间未并行 Gradle/R8、录屏或其他设备自动化。
它不是全新首装证明，也不抵消上表三轮专项失败；最后一次 page 准备超时的具体未满足条件
仍待增加失败快照区分。

每轮包含独立 `input.json`、`device.log`、`runtime.log`、`result.json`；APK 身份不拼接，
首轮或隔离通过不合成为整组通过。目录中的 `launch.json` 保存进程/AVD/启动参数与时间。

## 设备断言覆盖

- `PreparedFolderDocumentsProvider` 的 12 份普通文件包含同级 `same.txt` 两份不同 bytes、
  同名目录两支的重复 `same-dir/inside.txt`、文件与目录同名、无扩展名 PNG、`.bin` 显式
  `text/plain`、深 9 层、Unicode 与零字节。比较 path/name/type/size/mtime/POST SHA 的频次
  多重集和完整 bytes，不按逻辑路径 Map 去重。
- 显式 mtime 0、负值与常规时间分别保真。未知时间的预期值独立读取实际物理 payload stat，
  并要求落在复制前后实际时钟窗口；不从生产 manifest 抄预期。实际第二次 File.arrayBuffer/
  POST 再验证全部 12 份仍可读取，native 完成不重写该时间，只有一次 change。
- 系统 TREE Back 保留原 FileList、原文档/GET/随机 nonce、原租约与再次 POST 的 bytes。
  真正第二 Gecko 确认显示后重建 Main，保留原 token 和文档，仅交付一次。
- 旧 owner 在真实 TREE 覆盖期间关闭，实际旧系统 Use/Allow 结果不交付给下一 owner；下一
  owner 发自己的 TREE 和原生确认后独立上传。**这是迟到系统结果，不是迟到第二 native prompt。**
- 普通目录、私密 owner 关闭、空根真实 change、实际 native DENY、无扩展名显式 PNG 仍验；
  virtual 与正常文件混合时整体失败、零 change/bytes、租约与临时授权清理。
- `PreparedFolderFilesystemIntegrationTest` 实际在 Android 创建 leaf、ancestor directory、
  unlisted directory 三类符号链接。每项有正常控制/恢复控制，初次 validate 和原 snapshot 的
  validateFor 均须拒绝，链接保持、等长的目标 bytes 不变。没有创建失败 skip。此专项只证明
  Android 文件系统校验，不扩大为恶意外部 provider 或 UI 上传全矩阵。

## 测试驱动修正与剩余门

网页触摸现在先要求真实 Main RESUMED 且实际 window focus，再核对 Gecko view 可见与触摸
范围并记录非敏感诊断；原坐标、20/30 秒期限和业务断言不变。首次 Inspect 失报仍未唯一归因。
后续 12/13 的 helper 在取得 Use 后未观察到真实 grant 就结束；旧代码将单次 DocumentsUI
根窗口不可用判为完成，不能以此证明授权结束。现在必须已观察 Use、匹配实际授权标题和点击 ALLOW 后才允许按窗口退出结束，
不把窗口缺失算成功，不跳过系统授权，不自动允许网页的原生目录确认。

仍待：真实迟到第二 native FolderUploadPrompt、选择/复制中重建与 IO owner 作废、进程死亡、
保留已有选择时拒绝第二确认、其他 provider/网站、私密完整持久化取证、native IPC/OOM 边界、
上游升级 ABI 复审、独立前置类与真机矩阵、R8 后同包 ABI/ARM64 实际运行与正式签名。本批只支持受限
普通文件目录；virtual 转换仍明确整体拒绝，不宣称已支持。
