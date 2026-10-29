# B13 真实录音上传首批验证

本批只新增设备测试与夹具，不改变生产录音路径。独立`AudioCaptureIntegrationTest`组合复用原上传夹具，
原`FileUploadIntegrationTest`的18项及既有camera helpers保持不变；不继承或重复执行原18项。
录音取消、私密、完整生命周期、其他处理器和物理拾音仍未验收。

## 固定处理器和实际前置

使用官方Fossify Voice Recorder **1.7.3/code20**，固定源码提交
`fca742d5dee533a32aee54c4a20e4cfa2d41fc18`；不称其为最新版本，不包含在Miru发布包中。
已下载APK为7,324,360字节，SHA-256
`ee8d6f2121a432181ff0108808c9813883f5065facb944343d3bfde32e770d25`；
SDK签名校验通过，证书SHA与独立F-Droid元数据pin
`affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292`一致。
原发布响应、签名/badging和源码机制见`artifacts/b13-upload-lifecycle/audio-capture-handler-contract.md`及`audio-handler-binaries/`。

受控API35/x86_64只读AVD启用虚拟音频，在安装与录制前实际执行`avd hostmicoff`并取得OK；
本批输入是零值，不采集宿主环境声音，不证明物理麦克风、可听内容或噪声/增益。
通过Fossify实际MAIN、系统麦克风/通知弹窗、内部目录选择及真实SAF，选取新建的
`Download/MiruAudioAcceptance-UUID`专用空目录。前置录制真实计时后经Cancel/Discard/Yes结束，确认目录空且返回Launcher。
没有`pm grant`、替换处理器或伪造权限/活动结果。

## 原始来源与交付证据

真实HTML`accept=audio/* capture`→Miru录音按钮→实际RECORD_SOUND→Fossify真实Record/时钟/Save。
保存前只读枚举专用目录，要求恰好一份新M4A；不写入或替换录音源。
独立线程在真实Save后观察Miru UID对对应原URI新出现READ grant，随后以普通Miru UID查询provider并打开原只读FD。
从原FD验证AAC音轨、采样率/声道、正时长及实际encoded sample；
完整原FD、专用目录真实外部原文件的只读shell bytes、网页实际POST三方字节相同，File name/type/size与provider一致。
不读取staging冒充源、不增加生产observer、不反射、不延迟生产IO或伪造FileList/native callback。

返回后receiver结束、READ/WRITE恢复原基线、persisted grant集合不变；
Miru文档租约保持可读直到关owner才删除，Fossify外部原录音仍保留。
持有原FD不延长URI grant，所有描述符/观察线程在finally关闭。

## 构建、失败与实际结果

证据根为`artifacts/b13-upload-lifecycle/audio-capture-integration/`，每轮独立input/device/runtime/result和APK摘要。

| 轮次 | 固定输入与终态 |
| --- | --- |
| 原31ef测试 | 0.5.8/E1BE主包；`audio-first-*` **0/1，52.453秒**，查找保存前M4A超时。真实录音及清理后原文件保留，未计上传通过。 |
| 修正helper | Android15实际`UiAutomationConnection`直接分词执行命令，不解释旧helper的shell引号/重定向。改为公开Rwe启动真实sh，经stdin传有界只读find/cat，分别有界读取stdout/stderr；退出标记仅在stderr，cat二进制stdout不混入标记。非零退出/异常stderr失败。另修权限窗口切换清理竞态。来源与实际SDK ABI保存在`shell-diagnostic-source/`。不改变20秒、新文件唯一性或源FD证明门。 |
| 0.5.8确认 | 测试SHA `6a20692dcf6c6ff4d44d18263cadea764777f48d5d3585b70f1bad108db8a517`；`audio-driver-fixed-*` **1/1，23.74秒**。实际READ=true、WRITE=false，音轨/源保留及网页字节断言均通过。 |
| 当前0.5.9 | `059-current-device/audio-current-*` **1/1，45.525秒**；固定主SHA `f097182109217b7b660ab38419538d3e5c1dc767d4df76ee60a7ea942f3e8e40`，测试SHA `e4e5a082bb60225d7093ea6e737b4b8f57554ca9e38e1381e3f449786c44a332`。全新5554实例、实际MIC/通知/SAF前置及console OK分别留证；READ/原FD/三方bytes/音轨/源保留全部通过。 |

当前版构建`059-current-source/build.log`成功（3分21秒），另补原命令未包含的纯JVM
`:core:common:test`（28秒、UP-TO-DATE）。按18个included模块实际任务和XML统计 **151/151，0失败/错误/跳过**；
engine90为FROM-CACHE，其余有测试模块为UP-TO-DATE，明确属于相同输入的结果复用，不写成151项全部重新执行。
Lint实际XML为0错误/22警告。原collector错误地假定所有模块都是Android，其失败和修正版均保留；
`recovery-snapshot.json`、`jvm-results-v2.json`和固定XML说明准确范围。

G主包F097与目录首批C主包6616身份不同：逐payload对比仅13个自有JS/许可文本的CRLF/LF差异，
归一化后全部一致，DEX/native/omni字节相同；仍分别记录APK SHA，不拼成同一个已测二进制。
当前录音1项是独立专项，不能与目录49或旧版117合并冒充本版完整常规回归。

## 单独运行与剩余范围

常规回归明确排除`AudioCaptureIntegrationTest`。该类要求已按上述实际步骤准备的AVD、固定处理器和参数：

```text
adb -s CONTROLLED_SERIAL shell am instrument -w -r -e class app.miru.browser.AudioCaptureIntegrationTest -e audioFixtureDirectory /storage/emulated/0/Download/MiruAudioAcceptance-UUID -e audioHostMicOff true app.miru.browser.test/androidx.test.runner.AndroidJUnitRunner
```

参数不是hostmic OFF的证据，必须另外取得实际console OK。缺前置会失败，不算通过或静默跳过。
剩余包括录音取消/拒绝、旧选择保留、owner/私密/context结束、重建/导航/IO取消/进程死亡、其他handler/格式/网站、
真机拾音、R8/ARM64运行及正式发行。本批不提升整个B13状态。
