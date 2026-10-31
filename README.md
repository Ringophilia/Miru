<div align="center">

<img src="design/miru-icon.svg" width="112" alt="Miru 图标">

# Miru

**見る — 像 Chrome 一样干净的浏览器，自带开箱即用的广告拦截，<br>网页里的视频一键交给好用的原生播放器。**

[![Platform](https://img.shields.io/badge/Android-8.0%2B-BE1625?style=flat-square&logo=android&logoColor=white)](#-构建)
[![Engine](https://img.shields.io/badge/GeckoView-158-321E19?style=flat-square&logo=firefoxbrowser&logoColor=white)](docs/UPSTREAM.md)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-775650?style=flat-square&logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![uBlock Origin](https://img.shields.io/badge/uBlock%20Origin-1.75.0-47643B?style=flat-square)](third_party/ublock/NOTICE.md)
[![License](https://img.shields.io/badge/License-MPL--2.0-BE1625?style=flat-square)](LICENSE)

[特性](#-特性) · [当前进展](#-当前进展) · [构建](#-构建) · [测试](#-测试) · [工程结构](#-工程结构) · [文档](#-文档索引) · [许可](#-许可与致谢)

</div>

---

## ✦ 设计原则

Miru 的取舍遵循产品规范 [MIRU-SPEC.md](MIRU-SPEC.md)（唯一需求来源），冲突时按顺序让步：

1. **少即是多** — 主界面只有地址栏、标签数、菜单；新标签页只有 Logo、搜索框、快捷方式。没有信息流，没有运营位。
2. **默认就对** — 广告拦截无需注册、无需配置；普通用户永远不必打开设置。
3. **视频是一等公民** — 检测到视频时出现轻量入口；播放器手势与主流视频 App 一致。
4. **一切操作可撤销** — 关标签、删下载、清记录都用 Snackbar「撤销」，而不是确认弹窗。
5. **动效服务于空间关系** — 动画只说明东西从哪来、到哪去，并尊重系统「移除动画」设置。

## ✦ 特性

<table>
<tr>
<td width="50%" valign="top">

### 🌐 浏览器

- **GeckoView 内核** + Mozilla Android Components，`BrowserStore` 为唯一标签状态源
- 普通 / 无痕标签组，会话与导航栈恢复；无痕不落盘并启用 `FLAG_SECURE`
- Compose 地址栏：地址 / 搜索判断，历史、书签、已打开标签建议
- 原生新标签页，Gecko 就绪前即可显示
- 本地历史与书签（SQLite，无同步、无遥测）
- 页内查找、桌面站点、长按菜单、跟手隐藏工具栏、清除浏览数据
- 摄像头 / 麦克风 / 定位的站点权限，网站许可与系统许可分离

</td>
<td width="50%" valign="top">

### 🛡️ 广告拦截

- 内置**未修改、官方签名**的 uBlock Origin，无需注册与配置
- 使用 uBO 原版站点面板、过滤规则与例外列表
- 工具栏盾牌显示真实拦截计数
- 扩展未就绪时加载的页面会给出刷新提示，而不是假装已拦截

### ⬇️ 下载

- OkHttp 分段下载 + Room 断点，进程被杀后自动续传
- 暂停 / 继续 / 重试，分组通知，MediaStore / SAF 落盘
- 分类、搜索、多选与离页后仍有效的撤销

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 🎬 视频嗅探与播放器

- 「本页视频」面板：有界读取清单 / 媒体头，显示真实清晰度
- Media3 播放器：长按倍速、双击快进、横滑拖动、PiP、媒体会话
- 内嵌字幕与音轨切换；本地 SRT / VTT / ASS 仅保存在内存
- 可调缓存上限与预缓冲；无痕播放不读写缓存与进度

</td>
<td width="50%" valign="top">

### ✨ 离线与超分

- HLS / DASH 离线：选择清晰度，关联音轨与字幕一起保存
- 离线缓存与播放缓存分区，清缓存不会误删离线视频
- 实时超分：**Anime4K**（CNN，动画线条）与 **AMD FSR 1.0**（通用）
- 自动模式按片源与设备负载保护：HDR、受保护内容、高倍速、PiP 直通

</td>
</tr>
</table>

## ✦ 当前进展

> [!IMPORTANT]
> Miru 仍在开发中，**尚无正式发行版**。下表中的「已接入」表示代码已合入并有专项证据，**不等于该里程碑完成验收**。逐条需求与证据边界见 [全量验收矩阵](docs/ACCEPTANCE.md)。

| 里程碑 | 内容 | 状态 |
| :-- | :-- | :-- |
| **M0** | 浏览器外壳、标签、会话、地址栏、历史书签、设置 | 🟢 已接入 · 真机性能 / 无障碍待验收 |
| **M1** | uBlock Origin 集成、断点下载 | 🟢 已接入 · 首装稳定性 / 自动更新待补 |
| **M2** | 视频嗅探、Media3 播放器与接管 | 🟢 已接入 · 详见 [M2 记录](docs/M2-VALIDATION.md) |
| **M3** | 清晰度探测、字幕音轨、离线、超分、缩略图 | 🟡 主体已接入 · HLS磁盘层/缩略图受控专项通过，完整矩阵推进中 |
| **B13** | 站点权限、网页提示与上传 | 🟡 0.5.8文件/capture18/18 · 0.5.9目录首批5/5、跨模块49/49，完整矩阵待验 |
| **M4** | 动效、网站矩阵、真机性能、完整验收 | ⚪ 未开始 |
| **iOS** | 按规范在 Android M4 之后启动 | ⚪ 未开始 |

最新已验证打包为 **0.5.12 ARM64开发包，108,127,610字节**，仍使用Android Debug签名；构建、完整载荷及体积见[0.5.12记录](docs/M3-CACHED-TIMELINE-UI-VALIDATION.md)，缩略图历史见[0.5.11记录](docs/M3-SEEK-PREVIEW-VALIDATION.md)，目录历史见[0.5.10记录](docs/B13-PREPARED-FOLDER-VALIDATION.md)，录音历史见[0.5.9记录](docs/B13-AUDIO-CAPTURE-VALIDATION.md)，完整上传范围见[上传验收记录](docs/B13-FILE-UPLOAD-VALIDATION.md)。

0.5.8固定Debug包已在API35/x86_64模拟器完成常规回归 **117/117**，另有首次启动uBO **1/1**、真实系统权限独立 **3/3**；两个外部重启类、ARM64运行与真机性能仍按[验收矩阵](docs/ACCEPTANCE.md)分别记录。

0.5.9的[目录首批](docs/B13-FOLDER-UPLOAD-VALIDATION.md)已验证真实系统TREE、原生二次确认、深层相对路径与原始上传字节。目录生命周期、特殊MIME/元数据及其他provider仍待；本版49项为跨模块专项集成，尚无完整常规回归或ARM64运行验收。

0.5.10 开发批次增加[目录元数据与生命周期](docs/B13-PREPARED-FOLDER-VALIDATION.md)：重复逻辑路径、显式 MIME 和 mtime 的实际网页字节验证，以及取消、重建、迟到系统结果和 Android 真实符号链接测试。固定同包常规完整回归 **130/130 通过（734.586秒）**，含本批全部13项；此前专项12/13及网页准备超时继续保留。重启、系统权限和录音四个独立前置类仍排除，不计通过。R8/shrink构建与上述ARM64打包通过，ARM64/R8实际运行及正式签名仍待验。

后续主包不变、仅测试加入失败快照：新实例首次Main的原负例1/1，真实Android系统权限另3/3通过，各自独立记录，不合并进上述130项。旧网页准备超时未复现，根因仍待查。

0.5.11 开发批次接入[拖动缩略图](docs/M3-SEEK-PREVIEW-VALIDATION.md)：10秒/60张内存LRU、受控提帧取消、认证/缓存隔离和真实拖动界面。播放器JVM27/27、提帧后端独立22/22、完整UI另5/5通过；同包常规完整 **135/135通过（881.462秒）**。独立前置类与capability包排除不计通过；R8/shrink构建及1538项完整载荷、17个ARM64库、Gecko资源、签名/对齐/体积打包验证通过。ARM64/R8实际运行、正式签名及真机矩阵继续待验。

0.5.12/code20接入[P11普通HLS磁盘进度条](docs/M3-CACHED-TIMELINE-UI-VALIDATION.md)：按实际原生wrapper/选轨发布不连续区段，读取失败/切源/清理撤旧层；JVM72/72、独立模块41/41、真实UI4/4通过，含停供后实际93秒蓝帧与无缓存缺口失败。同包常规完整139/139通过（941.713秒），四个独立前置类及capability仍排除不计通过。5475738干净源R8构建及1538payload/ARM64体积打包验证通过，实际运行和正式签名仍待。[独立MP4预验](docs/M3-CACHED-RANGES-PREFLIGHT.md)的复杂2过1失败继续保留。DASH/MP4等、复杂HLS及跨会话未知资源仍待，P11完整验收未完成。

<details>
<summary><b>历史已验证打包：0.5.4（ARM64 开发包）</b></summary>

<br>

| 项目 | 值 |
| :-- | :-- |
| 本地产物 | `artifacts/releases/Miru-0.5.4-arm64.apk`（不纳入版本库） |
| 对应源码 | `97d8be1` |
| 体积 | 107,966,640 字节（严格十进制 110 MB 体积门内） |
| SHA-256 | `215e07625fa086328433f3d0cebc6391179706ac3c4741a3755a0e8ed52c753a` |
| 打包检查 | 压缩字节复用、SDK 对齐、同证书重签；1517 项载荷逐项保留，17 个原生库均为 ARM64 |
| Gecko 资源 | 4078 项原 payload 保持 · 1 项定位策略修改 · 5 项官方 blocklist 新增 |
| 专项结果 | 站点 / 混合下载 7/7，全新模拟器真实系统权限 3/3 |

仍为本机 Android Debug 签名；**没有 ARM64 实机运行、全套回归或正式发行验收**。打包工具见 [apk-release-buildlogic](tools/apk-release-buildlogic/README.md)，验证记录见 [B13 权限记录](docs/B13-PERMISSIONS-VALIDATION.md)。历史版本的回归结果（含失败项）保留在 [验收矩阵](docs/ACCEPTANCE.md) 的证据索引中。

</details>

## ✦ 构建

**环境要求**：JDK 21 · Android SDK Platform 37.0 · Build Tools 37.0.0。依赖版本固定在 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)，工程自带 Gradle Wrapper，无需全局安装 Gradle。

1. 用 Android Studio 打开本目录，将 Gradle JDK 设为 **JDK 21**。
2. 在 SDK Manager 安装 Android 17 / API 37.0，或运行：

   ```bash
   sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
   ```

3. 在未提交的 `local.properties` 中写入 `sdk.dir=<SDK 绝对路径>`，或设置 `ANDROID_HOME`。
4. 构建调试包：

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

| | Debug | Release |
| :-- | :-- | :-- |
| 最低系统 | Android 8.0（API 26） | Android 8.0（API 26） |
| ABI | `arm64-v8a` + `x86_64`（模拟器） | 仅 `arm64-v8a` |
| 签名 | 调试密钥 | 读取未提交的 `keystore.properties`；未配置时回退调试密钥，仅供开发安装 |

## ✦ 测试

```powershell
# JVM 单元测试与 Lint
.\gradlew.bat --no-daemon testDebugUnitTest :core:common:test :app:lintDebug

# 设备测试（需连接模拟器或手机）
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=app.miru.browser.DownloadRestartTest,app.miru.browser.OfflineRestartTest,app.miru.browser.RuntimeSitePermissionIntegrationTest,app.miru.browser.AudioCaptureIntegrationTest" "-Pandroid.testInstrumentationRunnerArguments.notPackage=app.miru.browser.capability"
```

设备测试的网页由测试进程内的 loopback HTTP 服务提供；全新安装 uBO 时仍可能访问 Gecko 远程设置。独立的手动 fixture 可通过 `node tools/m1-fixture-server.mjs` 启动。

磁盘资源绑定另有独立模块设备测试：`.\gradlew.bat --no-daemon :feature:player:connectedDebugAndroidTest`。它使用真实SimpleCache和受控来源，准确范围见[绑定验证记录](docs/M3-CACHE-BINDINGS-VALIDATION.md)，不合并进应用常规回归计数。

> [!NOTE]
> 上面的常规命令**排除了四类需要单独运行的专项**，排除不计为通过：
>
> - **`DownloadRestartTest` / `OfflineRestartTest`** — 分阶段运行，中间强制停止进程。方法见 [M1 记录](docs/M1-VALIDATION.md) 与 [离线记录](docs/M3-OFFLINE-VALIDATION.md)。
> - **`RuntimeSitePermissionIntegrationTest`** — 在四项系统权限均未授权的 API 35 实例上顺序运行，实际点击 Android 权限弹窗。见 [运行方法](docs/B13-RUNTIME-PERMISSIONS-TEST.md)。它与使用预授权的站点权限用例验证范围不同，不能相互替代。
> - **`AudioCaptureIntegrationTest`** — 需实际录音处理器、专用目录、音频启用与hostmic关闭的独立实例；0.5.9真实原FD/网页字节专项1/1通过。见[录音验证与运行前置](docs/B13-AUDIO-CAPTURE-VALIDATION.md)。
>
> 模拟器结果不能代替规范要求的真机性能、兼容性和无障碍验收。

## ✦ 工程结构

```text
Miru
├── app                    Application / AppContainer / Activity / 导航
├── core
│   ├── common             地址判断、搜索引擎定义（纯 JVM）
│   ├── data               DataStore 设置，本地历史与书签（SQLite）
│   └── design             主题、设计与动效 token、矢量字标
├── browser
│   ├── engine             GeckoRuntime / BrowserStore / SessionStorage / 权限与提示协调
│   └── toolbar            Compose 地址栏
├── feature
│   ├── ntp                原生新标签页与快捷方式
│   ├── tabs               普通 / 无痕标签切换器
│   ├── menu               主菜单
│   ├── history            历史记录：日期分组、搜索、可撤销删除
│   ├── bookmarks          书签：星标、列表、编辑
│   ├── adblock            uBO 状态、站点面板、全局开关
│   ├── downloads          分段断点下载、通知、MediaStore / SAF、下载管理
│   ├── media-detect       媒体模型、按标签隔离的内存索引、清晰度探测
│   ├── player             Media3 播放器、手势、缓存、字幕音轨、PiP
│   ├── offline            HLS / DASH 离线、独立缓存、前台服务、持久索引
│   ├── superres           Anime4K / FSR 渲染链、设备保护与性能测试
│   └── settings           设置与关于
├── design                 跨平台 token JSON 与可编辑 SVG 图标
├── third_party            第三方许可、来源与固定资源说明
├── tools                  fixture 服务器、打包与 blocklist 构建逻辑
└── docs                   进度、验收矩阵与各批次验证记录
```

### 架构要点

- **单一状态源** — 标签与导航状态只来自 Android Components 的 `BrowserStore`；Compose 读取不可变快照，不维护第二套标签模型。
- **启动不等内核** — 原生新标签页在 Gecko 就绪前即可展示；Gecko 按其 API 要求在主线程初始化，会话 I/O 在后台执行。
- **无痕即不留痕** — 普通标签用原生会话序列化保存导航栈；无痕标签不写入会话文件、不截缩略图、不读写播放缓存。
- **克制的上游借鉴** — 参考 reference-browser 的核心组件装配，不带入其账号、同步与遥测应用层。因 Places 存储会引入遥测与同步依赖，历史书签改用本地 SQLite，偏差说明见 [PROGRESS.md](docs/PROGRESS.md)。

## ✦ 文档索引

| 主题 | 文档 |
| :-- | :-- |
| 产品与实现规范 | [MIRU-SPEC.md](MIRU-SPEC.md) |
| 实现进度 · 全量验收矩阵 | [PROGRESS.md](docs/PROGRESS.md) · [ACCEPTANCE.md](docs/ACCEPTANCE.md) |
| 上游版本与工具链 | [UPSTREAM.md](docs/UPSTREAM.md) |
| 浏览器外壳 | [M0](docs/M0-VALIDATION.md) · [浏览器核心](docs/BROWSER-CORE-VALIDATION.md) |
| 广告拦截与下载 | [uBO 接口与限制](docs/M1-ADBLOCK.md) · [blocklist](docs/M1-BLOCKLIST-VALIDATION.md) · [M1 记录](docs/M1-VALIDATION.md) |
| 播放器 | [M2](docs/M2-VALIDATION.md) · [0.3.1 补齐](docs/M2-031-VALIDATION.md) |
| 媒体增强 | [M3 首批](docs/M3-VALIDATION.md) · [离线](docs/M3-OFFLINE-VALIDATION.md) · [超分](docs/M3-SUPERRES-VALIDATION.md) |
| 权限与网页提示 | [B13 权限](docs/B13-PERMISSIONS-VALIDATION.md) · [网页提示](docs/B13-PROMPTS-VALIDATION.md) · [文件上传](docs/B13-FILE-UPLOAD-VALIDATION.md) |
| 开源合规 | [开源检查](docs/OPEN-SOURCE-REVIEW.md) · [第三方说明](third_party/README.md) |

## ✦ 许可与致谢

Miru 由 [**Ringophilia**](https://github.com/Ringophilia) 与 **xiaomao** 共同开发，署名见 [AUTHORS.md](AUTHORS.md)。

- 项目自有代码、构建脚本与文档采用 [**Mozilla Public License 2.0**](LICENSE)，范围见 [NOTICE](NOTICE)。
- 第三方组件保留各自的许可证与署名：GeckoView 与 Android Components（MPL-2.0）、uBlock Origin（GPL-3.0，原样内置）、Media3 / OkHttp / AndroidX（Apache-2.0）、Anime4K 与 AMD FSR（MIT）等，详见 [第三方说明](third_party/README.md)。
- 当前依赖链包含受 Google 专有条款约束的 Play services FIDO 组件，**不能据此宣称整个构建产物的依赖均为自由开源软件**。

图标由维护者以《Ringo EXPO 08》封面为灵感、通过 AI 生成，素材授权范围与来源见 [design/PROVENANCE.md](design/PROVENANCE.md)。

<sub>Miru 与椎名林檎、相关唱片公司、Mozilla 或 Google 均无隶属或背书关系。</sub>

<div align="center">
<br>
<img src="design/miru-wordmark.svg" height="28" alt="Miru">
</div>
