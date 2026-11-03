# 0.5.20 Release 包与受控运行

2026-11-03，生产源码为25559eb中的0.5.20/code28，与已通过151项的应用输入相同。
本记录只提升明确覆盖的构建/打包/受控运行门，不等于完整P11、M4或全规范完成。

## 冻结源码与 ARM64 包

`seek-undo-release-build`先核对成功的`seek-undo-fix-routine-01`：151个status0、终态OK、
ADB0。567项应用/构建/测试输入与对应Debug冻结清单一致，仅排除文档及独立Release探针。
随后完整冻结634项源码，R8/资源缩减/lint **2分38秒成功，0错误/27警告，无源码漂移**。

stock ARM64 APK为111303311字节，SHA
`88d2666da4e6dc14a108cf0c1caffdb4e58181e4fb0a4b776225e049b6d7bc41`。
以固定Zipflinger9.1.1和已完成的0.5.18压缩缓存执行现有`optimize-release.ps1`，
完整1538项payload与当前stock相同、SDK16KB对齐、签名有效、原证书集合一致。

最终`artifacts/releases/Miru-0.5.20-arm64.apk`：

- **108156282字节**，严格小于110000000字节。
- SHA **`025968fd1151ec1b42cbd36571ed86634ab9decea3d61fae21fc259b9a93c20c`**。
- 17个ELF64/AArch64库，无其他ABI。此项是包内ABI元数据，不是ARM64实际执行。
- Gecko的4077项原资源、两处精确补丁、五项固定增补通过；omni SHA仍为
  `f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`。
- 证书仍是本机Android Debug，SHA
  `5aa1a00e59b8638298dbddcc5a61053ca245249136edb67199e0c9a3e4cb1f84`，未声明正式发行签名。

unsigned/signed scratch按工具finally清除，日志、payload/签名/ABI/omni报告保留。
外层曾误用未设置的LASTEXITCODE而误报Gecko校验失败；实际验证报告为PASSED，
已核对报告APK SHA与本包相同后继续ABI检查，没有将未通过的资源检查记成通过。

## 相同 DEX 的 x86 受控运行

`seek-undo-x86-build`只通过固定init脚本覆盖release ABI，无新增应用类、debug开关或keep规则。
同一634项源码无漂移，25秒构建成功、lint0/27。x86 APK116679092字节，SHA
`69580b90fc1c1cf6ce5022135b47990497b6e4d3dd82ee885d9fda9d4ce5b485`，17个ELF64/x86_64库。
其唯一`classes.dex`与最终ARM64包**逐字节相同**，10181672字节、SHA
`4864a7a317a73adac12811c8e563a6dcb1230f8d0b6b880e86976e94e0de48c1`，omni也相同。

独立Java框架探针无应用类、Kotlin运行库或目标debug接口；最终harness-06为345961字节，
SHA `636c78f0b781402f6b6d7ebcbfd04d1c16d8e39bc2153936a15e0e8a6766a2dc`，
13个自有/允许的编译器注解类，7项源码/素材输入冻结无漂移。两份媒体的包内SHA分别核对。
首次harness-01因AGP9不允许assets.srcDir(provider)失败，改为生成资源API；失败日志保留。

四轮未完成/失败的运行保留在runtime-01至04：初始AV只跳到9秒，冷重开产生额外上游；
随后增加完整预热时先遇到0→0公开进度动作无变更，再遇到Play节点操作拒绝，以及预热进度
停0/反复操作。它们各2项通过，均不计为3/3。导航改为读取相同进度时不重复设置、刷新节点，
仅在进度停止时启动播放，并记录真实Range；目标Release APK从未因此改动。

最终`seek-undo-runtime-05` **3/3，runner35.907秒，ADB0、终态OK，无负状态**：

1. 通过公开控件seek，实际红帧；销毁Activity、停供原源、新Activity再seek/红帧，零新增上游。
2. 普通浏览器入口中真实服务的Gecko文档标题可见，源端收到页面请求。
3. AVC＋双AAC素材先从起点实际播放到12000ms（13475ms，Play尝试1次），再公开请求9秒；
   销毁Activity、停供源、新Activity重开，实际蓝帧。AV源仅一条206请求[0,176805]，
   冷重开前后计数均为1。first/cached蓝帧RGB均[1,0,255]，cached PNG的源内时间标记为9.000。

安装输入的SHA/大小与临时pull相同，pull在finally删除，没有运行目录APK副本。
四张原生PNG及哈希、源Range、进度、报告和logcat保留。该AV检查是整对象缓存的视频输出，
不宣称PCM语言身份、部分洞、实际R8灰条像素、全部R8矩阵或ARM64 native运行。

## 后续门与磁盘

新Release与Debug151项各自保持输入边界。正式签名、ARM64物理机、完整R8/格式/权限/资源/
鉴权/清理矩阵、B帧/复杂编辑及其他容器、AAC精确preroll/EOS、长片内存/取消、M0–M5其他
条目继续，不能以此3项替代全规范。未增加永久APK保留：发布后已按最近两份ARM64、最近
两份Debug和最新stock执行清理；x86运行输入在本批活跃时由进程/启动时间租约和Keep保护。
