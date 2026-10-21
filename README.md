# Miru

Android 原生浏览器，包名 `app.miru.browser`。产品需求以 [MIRU-SPEC.md](MIRU-SPEC.md) 为准。

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

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。Android 8.0 及以上；当前包含 `arm64-v8a` 与模拟器 `x86_64`。发布使用 AAB 按 ABI 分包；当前调试包未签发布密钥。

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
