# B13 prepared-folder 合同（0.5.10/code18）

本批源码扩展目录元数据与同级重复 DISPLAY_NAME。0.5.9 公开 root 路线的已有设备结果见
[首批验证](B13-FOLDER-UPLOAD-VALIDATION.md)；它们不证明本版 manifest、R8 或特殊 metadata
通过。本批已构建并执行 JVM 和真实设备专项，确切包身份、逐轮结果与失败保留见
[本版验证记录](B13-PREPARED-FOLDER-VALIDATION.md)。完整 B13、R8/ARM64 运行仍未验收。

## 复制与完整性

非空目录统一使用一个真实 root，其叶名是用户选择的原始根名。每份普通文件只打开一次，
复制到 `Root/.miru-payload/<私有序号>/<原始basename>`。源 documentId 不进入物理路径、逻辑路径或日志。
全部物理 payload 都在该实际授权 root 内；没有 root 外文件，也没有磁盘 manifest/sidecar。
原始逻辑路径按每次出现逐项保存，重复路径不会用 Map 去重。同级同名文件、同名目录、文件与目录同名
在此布局不覆盖另一项。共享普通文件 ID 可在不同路径出现；目录祖先 ID 重复则整组失败。

根查询、children 查询、打开、复制、canonical/NOFOLLOW 与完整 inventory/bytes 校验全在 IO。
`PreparedUploadFolder` 私有构造器只由完整校验产生不可变快照；绑定原始 native record 前再次在 IO 校验。
验证要求每份文件都是 root 的真实普通可读后代、实际 basename 与逻辑叶名相同、物理路径唯一、
每份真实 size/累计 bytes 与复制结果相同，实际树中没有清单外文件、缺失文件、链接或特殊对象。
同前缀兄弟 `Root-other` 不属于 `Root`，不使用字符串前缀授权路径。

预算为 512 份文件、4096 个源节点、实际剩余空间保留 64 MiB，以及累计 1 MiB 源 metadata/逻辑路径。
单个 documentId 最长 16 Ki 字符；单次 provider children 返回的 metadata 也有 1 MiB 上限，
最终 manifest metadata 有独立 1 MiB 上限。物理 inventory 最多4096节点。任何预算或读取/查询/校验失败
都回收整份 lease，不截断后成功，不重开已读源流后静默换路线。

零文件目录与只有空子目录的目录仅创建真正空 root；遍历必须完成，才能使用原公开
`FilePrompt.confirm(Context, file://Root)`。不创建私有 payload 子目录，不能让空表扫描到内部 ID。
FileList 无法表达空子目录；完整 `webkitEntries` 目录模型未验收。

## 固定 Gecko 结果字段

同包 `MiruGeckoSessionBridge` 在 Main 只写不可变快照，不做 filesystem/provider IO。
原 `Folder.onSelected` 仍是唯一回调入口；`PreparedNativeFolder` 必须匹配原 native record，
只能消费一次，且实际 URI 必须与能力中的真实 root 完全一致。

| 字段 | 值与约束 |
| --- | --- |
| `files` | 恰好一个真实 root 的绝对路径，继续触发 Gecko 自身的目录授权及真正二次确认 |
| `filesInWebKitDirectory` | 完整非空逐项数组；不得在过滤/异常后发送空表 |
| `filePath` | 该 root 内已完整复制并校验的真实普通文件路径 |
| `relativePath` | `/原根名/逐级原DISPLAY_NAME`，保留首个 `/`；不含内部 ID |
| `type` | provider 非空普通具体 MIME；去两端空白并小写，拒绝参数、通配符和非法受限名称；不作内容嗅探声明 |
| `lastModified` | provider 毫秒值；null 完全省略，显式0与负值保留；超出±9,007,199,254,740,991整组失败 |
| `name`、`size`、`uri` | 不伪造这些字段；File.name 来自真实 basename，size 来自真实文件，源 SAF URI 不进入 Gecko manifest |

mtime 上限来自实际固定 `GeckoBundle.putLong` 把 long 存为 double 的实现，不能承诺范围外整数保真。
真实 APK 的 JS 先 `File.createFromFileName(filePath,{type,lastModified})`、再
`setMozRelativePath(relativePath)`，随后解析 root；File 对象创建可能早于二次确认，网页交付仍须
Gecko 的真正 `FolderUploadPrompt` 允许。缺少 leading slash 会使 `webkitRelativePath` 丢首字符。

## 新增非公开 ABI 与事务归属

新增依赖固定 GV `158.0.20261021001945` / AC `158.0.2` 的：

- `BasePrompt.ensureResult(): GeckoBundle`，package-private。
- `BasePrompt.confirm(): PromptResponse`，protected；通过同包显式 BasePrompt 引用直调。
- `GeckoBundle.putStringArray`、`putBundleArray`、`putString`、`putLong`，public。

没有反射、修改 SDK class、构造 NativeCallback/ActivityResult/FileList，或调用会重建 manifest 的
`FilePrompt.confirm(Context,Uri[])` 完成非空结果。桥继续检查 MOZILLA_VERSION、MOZ_APP_VERSION
与 MOZ_APP_BUILDID；编译常量检查不等于动态 ABI 探测，构建仍必须保留现有 AAR SHA pin
`a3af9d5ffb1f898df5b8782d083b69c4274c4f3275e31f3dac33d0d2aa16c692`。
实际 classes.jar SHA 为 `0cba1a1d88862d287ee8ab21a6a0bf47385cbea02c276d38c690f0a03f2fd794`，
ABI 原始证据在 ignored `artifacts/folder-upload/prepared-manifest-sdk-abi/`。

写结果前先占用现有每 session 单槽 native fence。parent-record、原 NativeUploadDocument、
BrowserPrompts owner/hardGeneration、目录名附加核对、30秒 appearance timeout 与 tombstone 均保留。
新增 ABI 的 LinkageError 转为失败关闭，不能把 dismiss 记为 confirmed；失败也不猜测清除 native fence。
真实第二 prompt 仍由 Gecko 产生；允许前不能交付网页；拒绝/owner结束回收 lease；成功保留到文档结束。
receiver 结束仅释放本次系统结果临时 TREE grant owner，不 takePersistable、不全局 revoke 用户已有授权。
普通单文件、多文件与 capture 路径仍使用原 PreparedNativeFiles/公开 confirm。

## 验证边界

候选 IO JVM 测试包含独立 payload 多重集、显式 MIME/原名/0及未知时间、深路径与 Unicode、
同名目录及文件/目录冲突、真正空 root、累计 metadata/bytes/节点预算、查询/读取错误、阻塞取消、
inventory 缺项/多项/size变化、根外/同前缀兄弟/非法逻辑路径/链接。engine JVM 实际 104 项中
103 通过、1 项因 Windows 无法创建符号链接而跳过。另有 Android 真实 leaf/ancestor/unlisted
链接及交付前 validateFor 重检的三项专项，不能将它们写成 Windows 被跳过项实际执行通过。

真实 DocumentsUI TREE、provider、Gecko 两阶段及网页 FileList/POST 的受控逐项多重集验证已覆盖
无扩展名显式 MIME、扩展名冲突 MIME、同路径不同 body、mtime、文件计数/bytes及内部路径不泄漏；
具体执行与整组边界以本版验证记录为准，不能扩大到其他 provider 或网站。
本版 R8/shrink 构建及 APK 内原始 omni/两项精确 patch/五项新增的严格验证已通过；R8 后同包
ABI 的实际运行仍待，不能用构建与归档检查代替。0.5.9 设备/发行证据不能代替本版证据。
虚拟转换、恶意循环、不可读或非法 metadata 继续明确整组拒绝，不能把拒绝写成普通 metadata 已支持。
native/IPC 资源耗尽时上游可能丢项的边界仍未证明；本批不宣称解决上游 OOM/IPC 或 provider 原子快照。
