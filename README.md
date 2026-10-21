# Miru

Android 原生浏览器，包名 `app.miru.browser`。产品需求以 [MIRU-SPEC.md](MIRU-SPEC.md) 为准。

由 [Ringophilia](https://github.com/Ringophilia) 与 xiaomao 共同开发。源码：[Ringophilia/Miru](https://github.com/Ringophilia/Miru)。开发者署名见 [AUTHORS.md](AUTHORS.md)。

项目自有代码采用 [Mozilla Public License 2.0（MPL-2.0）](LICENSE)。第三方组件保留各自许可证和署名，详见 [第三方说明](third_party/README.md)与[开源检查记录](docs/OPEN-SOURCE-REVIEW.md)。当前依赖链包含受 Google 专有条款约束的 Play services FIDO 组件，不能据此宣称整个构建产物的依赖均为自由开源软件。

图标由维护者以《Ringo EXPO 08》封面为灵感，通过 AI 生成；素材的授权范围与来源见 [design/PROVENANCE.md](design/PROVENANCE.md)。Miru 与椎名林檎、相关唱片公司、Mozilla 或 Google 没有隶属或背书关系。

目前在实现 **M0 开发预览**：GeckoView / Mozilla Android Components 浏览核心，Kotlin Compose 外壳、原生新标签页、普通与无痕多标签、地址输入与搜索、关闭撤销、会话恢复、主菜单、历史记录、书签及基本外观设置。

uBlock Origin、下载和原生播放器属于后续 M1–M3，当前版本不会声称已启用广告拦截。

## 构建

需要 JDK 21、Android SDK Platform 37.0、Build Tools 37.0.0。依赖固定在 `gradle/libs.versions.toml`；工程使用 Gradle Wrapper，不需要全局安装 Gradle。

1. 用 Android Studio 打开本目录，并将 Gradle JDK 设为 JDK 21。
2. 在 SDK Manager 安装 Android 17 / API 37.0，或运行 `sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`。
3. 在未提交的 `local.properties` 设置 `sdk.dir=你的SDK绝对路径`，或使用 `ANDROID_HOME`。
4. 运行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core:common:test :browser:engine:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。Android 8.0 及以上；调试版包含 `arm64-v8a` 与模拟器 `x86_64`，当前 release 配置仅包含 `arm64-v8a`。发布签名从未提交的 `keystore.properties` 读取；未配置时使用调试密钥，仅供开发安装。

设备测试需要连接模拟器或手机，测试网页由测试进程内的 loopback HTTP 服务提供，不依赖公网。独立手动 fixture 可用 `node tools/fixture-server.mjs` 启动。

## 工程结构

```text
app                 Application / AppContainer / Activity / 导航
core/common         地址判断、搜索引擎定义及 JVM 测试
core/data           DataStore 设置、本地历史记录与书签（SQLite）
core/design         主题、设计与动效 token、矢量字标
browser/engine      GeckoRuntime / BrowserStore / SessionStorage
browser/toolbar     Compose 地址栏
feature/ntp         原生新标签页与快捷方式
feature/tabs        普通 / 无痕标签切换器
feature/menu        主菜单
feature/history     历史记录（日期分组、搜索、删除 / 清除可撤销）
feature/bookmarks   书签（星标、列表、编辑）
feature/settings    基本设置与关于
design              跨平台 token JSON 与可编辑 SVG 图标
```

浏览器标签与导航状态只来自 Android Components 的 `BrowserStore`。Compose 读取不可变快照，不维护第二套标签模型。原生新标签页在 Gecko 准备好前即可展示，Gecko 本身按其 API 要求在主线程初始化，会话 I/O 在后台执行。普通标签用原生会话序列化保存导航栈，无痕标签不写入会话文件。

当前借鉴了 reference-browser 的核心组件装配方式，未带入其账号、同步、遥测应用层。上游固定版本、来源、许可证与工具链验证见 [docs/UPSTREAM.md](docs/UPSTREAM.md)。运行时验收见 [docs/M0-VALIDATION.md](docs/M0-VALIDATION.md)。

## 后续

- M0 剩余验收与细节见 [docs/PROGRESS.md](docs/PROGRESS.md)。模拟器结果不能代替规范中的真机性能、兼容性和无障碍验收。
- M1：验证官方签名 uBO 的本地安装与扩展就绪屏障，接入盾牌、站点面板及可续传下载。
- M2：miru-bridge、Media3 播放器、手势、缓存、画中画。
- M3/M4：Ringo-Cove 超分移植、视频离线、动效与完整验收。
