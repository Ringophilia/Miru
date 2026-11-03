# 第一组日常浏览功能检查点（0.5.21）

本批按 AGENTS.md「当前阶段方向」执行，P11/P2保持冻结。版本0.5.21/code29，应用输入为64e37fa；659项源码完整冻结，Debug与Release完全相同，构建期间无漂移。功能提交包括地址栏建议/内联域名、网站图标、六个主题色、无痕关闭通知、可跳过的默认浏览器引导。

## 结果和证据范围

- 固定Debug：`artifacts/m3-cached-ranges/daily-browser-checkpoint-01/`，构建3分39秒，播放器JVM90/90，Lint 0错误/29警告。
- 全模块JVM和ARM64 R8/shrink：`daily-browser-release-build/`，构建6分38秒。264项中263通过、1跳过、0失败；跳过项是PreparedUploadFolderTest的符号链接拒绝用例，Windows创建测试符号链接缺少权限，不能计作通过。
- 常规应用回归：`daily-browser-routine-01/`完成163项，160通过、3失败，JUnit2114.458秒、runner2124.102秒，整套不通过。失败为QUALITY基准超时、QUALITY真实渲染未在60秒内完成60帧，以及历史图标显示超时；后续隔离复测不能抵消本轮失败。既定排除类是DownloadRestartTest、OfflineRestartTest、RuntimeSitePermissionIntegrationTest、AudioCaptureIntegrationTest，另排除capability包。排除不计通过。
- 同一Debug快照的无痕通知权限专项`daily-browser-permission-01/`已独立通过1/1（JUnit22.562秒、runner30.895秒），实际系统拒绝/不再询问/重新授权后通知恢复与关闭通过；测试后重新授予通知权限。
- 同包图标隔离复测`daily-browser-icons-isolated-01/`通过2/2（JUnit46.085秒、runner51.227秒），真实历史图片渲染和无痕缓存隔离通过；无法据此解释整套中的一次显示超时，更不能抵消原失败。
- 后续仅改图标测试夹具：先确认本次访问已写入历史，再通过真实搜索框定位自己的URL；超时报告本次记录是否存在、标题是否可见和页面是否持有图标，不输出其他历史内容。等待时限、实际图片像素和私密隔离断言未放宽。编译成功16秒，`daily-browser-icons-fixture-device-01/`两项通过；主APK仍是原检查点同一SHA，新测试APK及源文件哈希在`daily-browser-icons-fixture-01/result.json`。这不是整套重跑，也不证明原超时根因已解决。
- 本轮没有重跑独立播放器设备模块，也没有扩展P11；常规回归中的已有播放器用例保留。

设备为API35/x86_64 emulator-5554，Google SwiftShader软件GPU。仅模拟器结果；没有连接ARM64物理手机。没有本版本相同DEX的x86 Release运行证明，也没有正式发行签名。

## 功能证据

以下是前几批0.5.20开发包的功能专项；本次0.5.21把同名类纳入163项常规回归，整套已完成，160/163；图标用例在整套里失败，旧专项通过不抵消新失败。

- OmniboxSuggestionsIntegrationTest（3项）：HTTP在线建议/最多五条/填入/中文搜索、四引擎选择、取消旧请求/开关/无痕、历史域名高亮后缀/退格/原协议回车。本地服务验证，公网提供商在手机网络上的可达性仍待试用。
- WebsiteIconsIntegrationTest（2项）：真实Gecko页面声明图标，历史列表实际渲染，同进程复用、无痕不进入普通图标缓存、缺失回退。NTP/书签/标签页共享该UI接线；实际网站/ICO/SVG与重启后资源发现兼容仍有平台范围待验。图标只在内存缓存，图片上限8MB。
- AccentThemeIntegrationTest（2项）：六色即时生效、动态取色与保存预设切换、Activity重建保留选择、无痕深色。完整字体/顶底栏/真机组合未被这两项替代。
- PrivateTabsNotificationIntegrationTest（2项）：系统实际发布的通知操作/正文、后台关闭、普通标签保留、不能撤销恢复、旧无痕Cookie清除。权限专项另列。
- FirstRunIntroIntegrationTest（3项）：真实RoleManager选择和取消，默认浏览器接外部HTTP链接，已经默认状态，旋转后待开链接保留、跳过不重复开标签；系统默认浏览器在用例后恢复。

## ARM64包

本地包`artifacts/releases/Miru-0.5.21-arm64.apk`为108,468,497字节，小于110,000,000字节；SHA-256 `aa6dd530561e76ad8cb84c1f63ccac4103bc1d6b35f80357b371a084c0f4804b`。

打包证据在`artifacts/releases/Miru-0.5.21-arm64.apk.verification-20261103-165720-8d19f0bd/`。原stock输入111,599,922字节、SHA `1e1fcd34ae8aa31cba2c4648826ab2494d35affe3f01e04b3cc427b6a6f3ee29`，使用现有压缩复用工具后逐一核对1548项非签名payload的路径/长度/CRC/SHA/压缩方法一致，16KB对齐和签名验证通过。

17个native库均为ELF64/AArch64，无x86库；Gecko资源4077项原件、两项固定权限/导航补丁和5项固定增补通过。omni SHA保持`f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`。

证书仍是本机Android Debug，SHA `5aa1a00e59b8638298dbddcc5a61053ca245249136edb67199e0c9a3e4cb1f84`。不是正式签名或ARM64运行验收。unsigned/signed scratch在finally清理，映射/日志/报告保留。历史Debug/Release路径可被保留脚本裁剪，按清理账本查实，不认为历史二进制一直存在。

## 执行命令

JDK为`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`，SDK为`G:\Projects\Ringo-oneghg-ad-free\tools\android-sdk`。

```powershell
./tools/build-player-validation.ps1 -BuildName daily-browser-checkpoint-01
./tools/run-device-validation.ps1 -App -Routine -BuildName daily-browser-checkpoint-01 -RunName daily-browser-routine-01 -Expected 163
./tools/run-device-validation.ps1 -App -BuildName daily-browser-checkpoint-01 -RunName daily-browser-icons-isolated-01 -Selector app.miru.browser.WebsiteIconsIntegrationTest -Expected 2
./gradlew.bat testDebugUnitTest :core:common:test :app:assembleRelease :app:lintRelease --no-daemon --console=plain
./tools/clean-validation-artifacts.ps1 -Apply
```

Release构建与压缩/签名/资源验证的具体命令及完整输入摘要，在`daily-browser-release-build/build.ps1`、`package.ps1`、`input.json`及各pipeline.json中保留。

通知权限专项按[验证说明](VALIDATION-ARTIFACTS.md#private-notification-permission-case)先在宿主撤销权限/清除user-set和user-fixed，再以同一BuildName执行`app.miru.browser.capability.PrivateNotificationPermissionIntegrationTest`（Expected 1），最后重新授予权限。实际命令在`artifacts/daily-browser-checkpoint/batch-01/run-permission.ps1`和`permission-preparation.log`。

## 保留的失败和未覆盖项

本轮整套的三项失败未清除：QUALITY基准的FAST/BALANCED已得到333.7/398.7ms每帧，QUALITY未在45秒测量窗内得到60帧；另一真实QUALITY链在60秒观察期限结束时为53帧、约795.5ms每帧，initialized/active为true但未满足原采样门。这些是当前SwiftShader环境的实际失败，不当作手机性能结论，不修改门槛换取通过。历史图标在进入历史页后等待图片节点5秒超时；同包隔离2/2，整套场景的根因仍待定位，不能先推断为纯测试问题或宣称已修复。

此前onboarding-device-01的1过2失败是系统请求未使用Activity Result入口，已修复。device-02的2过1失败是测试框架按原Intent匹配生命周期、真实外部Intent更新后无法收尾；修复夹具后device-03三项通过。无痕通知device-01的2过1失败来自测试AppOps屏蔽后系统仍报通知可用；改为测试启动前撤销权限并点击真实系统拒绝按钮，单独能力用例验证。旧失败日志保留，不把失败轮写成通过。

B3查询保持/自定义搜索模板、B9完整私密取证/返回和下载路径、B14协议拉起确认/文件类型矩阵、B19字体/布局/图标全审查，以及真实手机/网站/签名/完整发行门继续保留。因此B3/B9/B14/B19仍为implemented，不提升整行或M0–M3阶段验收。下一组按AGENTS.md收尾B13网页权限/提示并查uBO首次安装超时。
