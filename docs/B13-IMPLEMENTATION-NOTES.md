# B13 站点权限、页面提示与外部协议：实现准备

本文是固定版本源码审查和下一批实现契约，**不是验收记录**。B13 仍待实现与真实设备验证，不能因通知权限一项通过而关闭整个 B13。

## 范围与固定证据

对应 `MIRU-SPEC.md` 第 5.3、5.4、11 节：站点信息中的连接安全性、证书、权限、Cookie 与站点数据；系统与网站权限请求；文件选择及页面提示；阻止弹窗后的“始终允许”；外部协议在用户确认后打开。

审查版本为 Android Components `158.0.2`，与项目当前版本目录一致。三个组件的 sources JAR 和 POM 均从官方 Maven 获取，只下载、解包至临时审查缓存，未运行 Gradle、安装依赖或设备测试。

| 组件 | 官方源码包 | sources JAR SHA-256 |
|---|---|---|
| feature-sitepermissions | [158.0.2 sources](https://maven.mozilla.org/maven2/org/mozilla/components/feature-sitepermissions/158.0.2/feature-sitepermissions-158.0.2-sources.jar) | `ddd3a99f94b7e453f2ced4cb931b8d048edd22eb09cdccddbd9b0d637decb08d` |
| feature-prompts | [158.0.2 sources](https://maven.mozilla.org/maven2/org/mozilla/components/feature-prompts/158.0.2/feature-prompts-158.0.2-sources.jar) | `cc31a5d36efeb7e51d5c2e1de3d5230c4fa17a2950f653a17ec9a9179ed54150` |
| feature-app-links | [158.0.2 sources](https://maven.mozilla.org/maven2/org/mozilla/components/feature-app-links/158.0.2/feature-app-links-158.0.2-sources.jar) | `7cb403f7c55116be7425a2857987d17fd9ff4ef57ff2994e9ef782d2b488521e` |

本机解包根目录：`C:/Users/tocml/AppData/Local/Temp/miru-ac-158/`。下文路径均相对该目录；临时缓存可能被系统清理，官方固定版本链接和摘要用于重新取得同一证据。

## 依赖审查边界

递归检查官方 POM 的非 test、非 provided、非 optional Mozilla 依赖闭包，包含根组件：`feature-sitepermissions` 31 项、`feature-prompts` 32 项、`feature-app-links` 18 项，无下载或解析失败。检查未发现 Glean、telemetry、Nimbus、Sync、FxA、Firebase 或 Sentry 依赖。部分源会调用 `support-base` 的本地 `Fact.collect`；本地 Fact 不是上传 SDK，也不能据此声称整个 APK 没有任何遥测能力。

这是固定 POM 的源码准备结论，不能代替接入后 Gradle 的最终运行时依赖和发行 APK 审计。相关 POM 会引入或升级：`androidx.activity:activity:1.13.0`、`androidx.fragment:fragment-compose:1.9.0`、`androidx.lifecycle:lifecycle-livedata:2.11.0`、Room `2.8.5`、Paging `3.5.1`、Material `1.14.0` 等。实际修改依赖后应检查解析冲突并重跑适当回归。

## 可用入口和签名

### 站点及系统权限

`feature-sitepermissions/mozilla/components/feature/sitepermissions/SitePermissionsFeature.kt` 第 107 行起：

```kotlin
SitePermissionsFeature(
    context: Context,
    sessionId: String? = null,
    storage: SitePermissionsStorage,
    sitePermissionsRules: SitePermissionsRules? = null,
    fragmentManager: FragmentManager,
    onNeedToRequestPermissions: OnNeedToRequestPermissions,
    onShouldShowRequestPermissionRationale: (String) -> Boolean,
    store: BrowserStore,
    // 另有样式、调度器、fullscreen 和可见性参数
)
```

它实现 `LifecycleAwareFeature`、`PermissionsFeature`，提供 `start()`、`stop()`、`onPermissionsResult(Array<String>, IntArray)`。第 196–237 行观察 `ContentState.appPermissionRequestsList` 和 `permissionRequestsList`。第 289 行起将系统权限结果交给请求；同时请求 coarse/fine location 时，获得其中一种即可满足位置部分，非位置权限仍须全部获准。

`concept-engine/mozilla/components/concept/engine/permission/PermissionRequest.kt` 定义 `uri`、`id`、`permissions`、`grant(...)`、`reject()`、`merge(...)`；每个请求必须完成，不能仅把底部面板关闭。

`browser-engine-gecko/mozilla/components/browser/engine/gecko/permission/GeckoSitePermissionsStorage.kt` 第 43 行起：

```kotlin
GeckoSitePermissionsStorage(runtime: GeckoRuntime, onDiskStorage: SitePermissionsStorage)
```

应与 `feature-sitepermissions/.../OnDiskSitePermissionsStorage.kt` 组合使用。Gecko Content 权限、磁盘媒体权限和临时许可是不同来源；只使用 Room 会漏掉 Gecko 已缓存的决策。接口支持 `save/update/findSitePermissionsBy/remove/removeAll/all`，其中查询和逐站点操作具有 `private` 参数；`saveTemporary/clearTemporaryPermissions` 负责临时授权。

`SitePermissionsRules.kt` 覆盖 camera、location、notification、microphone、autoplayAudible/Inaudible、persistentStorage、mediaKeySystemAccess、crossOriginStorageAccess、localDeviceAccess、localNetworkAccess。`SitePermissions.localStorage` 在此语境代表持久存储授权，不能误作 DOM 存储本体。

### 页面提示、文件与窗口

`feature-prompts/mozilla/components/feature/prompts/PromptFeature.kt` 第 269 行起有公开 Activity 构造入口。必需参数包含 Activity、BrowserStore、FragmentManager、TabsUseCases、FileUploadsDirCleaner、onNeedToRequestPermissions；还需明确关闭未实现的邮件掩码等功能回调。它提供：

```kotlin
start()
stop()
onActivityResult(requestCode: Int, data: Intent?, resultCode: Int): Boolean
onPermissionsResult(permissions: Array<String>, grantResults: IntArray)
onBackPressed(): Boolean
```

它观察 `content.promptRequests`；支持文件/目录、认证、JS alert/confirm/prompt、选项、日期时间、颜色、beforeunload、repost、弹窗等。未提供的密码管理/自动填充功能应禁用，并对不支持的请求完成取消，不能留下悬挂回调。

`concept-engine/.../prompt/PromptRequest.kt` 第 324 行的 `PromptRequest.File` 提供：

```kotlin
mimeTypes: Array<out String>
isMultipleFilesSelection: Boolean
captureMode: FacingMode
onSingleFileSelected: (Context, Uri) -> Unit
onMultipleFilesSelected: (Context, Array<Uri>) -> Unit
onDismiss: () -> Unit
```

`feature-prompts/.../file/FilePicker.kt` 为 `internal`，可作为文件、capture URI、读取授权、私有目录过滤和结果消费的固定版本参考。`FileUploadsDirCleaner(scope, ioDispatcher, cacheDirectory)` 应使用 application scope；`FileUploadsDirCleanerMiddleware` 在跨 host 导航时清理临时上传。需另外验证关标签、private 结束、取消与重启后的清理。

`feature-prompts/.../PromptMiddleware.kt` 限制重复 Popup 请求。`PromptRequest.Popup` 具有 `targetUri/onAllow/onDeny/onDismiss`。

`feature-tabs/mozilla/components/feature/tabs/WindowFeature.kt` 第 20 行：`WindowFeature(store, tabsUseCases)`，具有 `start/stop`；处理 `content.windowRequest` 的 OPEN/CLOSE，保留真实 engine session、parentId、private 标记，再消费请求。不能用重新导航 URL 代替原 window session，避免破坏 opener 与页面状态。

### 外部协议

`feature-app-links/mozilla/components/feature/app/links/AppLinksInterceptor.kt` 第 65 行起实现 `RequestInterceptor`；接入 `DefaultSettings.requestInterceptor`。

`AppLinksFeature.kt` 第 93 行起要求 Context、BrowserStore、FragmentManager，可注入 `RedirectDialogData -> RedirectDialogFragment` 工厂，提供 `start/stop`，观察 `content.appIntent`。`AppLinksUseCases` 提供 redirect 解析、应用名称、fallback、marketplace 与用户确认后的 `openAppLink`。

`AppLinksUseCases.kt` 第 158 行起会清除原始 intent 的 component、selector 和非必要 flags，添加 BROWSABLE，并防止回到浏览器自身。若适配其逻辑，这些约束必须保留；还需真实验证 Android 包可见性下目标应用的查询结果。

## 当前 Miru 接入与兼容选择

`BrowserController` 持有唯一 BrowserStore、TabsUseCases、SessionUseCases、GeckoRuntime；middleware 目前只有 UndoMiddleware 和 DeferredEngineMiddleware。建议在 Controller 内构造 Activity feature binding/工厂，不为 UI 暴露可任意修改的 store。

`MainActivity` 目前继承 `ComponentActivity`，没有 `supportFragmentManager`。使用上述现成功能时，可改为 `FragmentActivity`；后者仍继承 ComponentActivity，现有 Compose `setContent`、Activity Result 和 `context as ComponentActivity` 方案原则上兼容，但仍需旋转、saved-state、返回与 PiP 等回归。另一种选择是保留 ComponentActivity，基于固定 AC 请求/存储契约移植需要的逻辑；应保留 MPL-2.0 声明和来源，不假称直接使用了未接入的 feature。

Activity Result launcher 应在 Activity 创建阶段注册；permission/prompt feature 随 Activity 生命周期启停、重建后重新绑定。`BrowserViewport` 中 EngineView 跨设置、NTP、编辑 overlay 保留，不能只按网页 View 的创建/销毁启停这些功能。

当前 `siteInfo` 面板仅显示 secure 布尔状态、URL、广告拦截入口。`browser-state/.../state/SecurityInfo.kt` 已有 host、issuer、X509Certificate，需要补到只读 UI 投影；还需权限查看/重置、Cookie 与本站数据操作。

## 必须规避的固定源码陷阱

1. **Compose 自绘没有可直接替换的公开权限 dialog factory。** `SitePermissionsDialogFragment` 是 `internal open`；SitePermissionsFeature 的公开 PromptsStyling 只有 gravity、宽度和按钮颜色，DialogConfig 只有预选“记住”参数。不能通过 public factory 直接换成 Miru Compose bottom sheet。完整重绘需要小范围 MPL 适配，或复用请求与存储协议实现 Miru UI。
2. **private remove 会触及普通 Room 行。** OnDiskSitePermissionsStorage 的 save/update 对 private 返回，find(private=true) 返回 null，但 remove(sitePermissions, private) 没有 private guard。GeckoSitePermissionsStorage.remove 会继续调用它；private 设置页直接调用可能删除同 origin 的普通持久记录。private 清理应只操作对应 Gecko/private 临时状态，或明确隔离存储实现。
3. **新增 Room 后，B20 必须同步清理。** 当前 SITE_SETTINGS 使用 engine.clearData，仅清 Gecko；未来 Room 中 camera/microphone 等记录若保留，清理后可能再次授权。将 SitePermissionsStorage.removeAll/相应站点重置与 Gecko 清理组成明确完成顺序，不能先显示成功再异步留下持久决策。
4. **Photo Picker 单选取消被吞。** `AndroidPhotoPicker.kt` 的 singleMediaPicker 回调只有 `uri?.let`；null 时不调用原 PromptRequest.onDismiss，也不 consume。启用该辅助类时必须补齐取消闭环。多选空结果、Activity 重建和迟到结果也需验证。
5. **默认外部认证流可能直接启动。** AppLinksInterceptor 即使 launchFromInterceptor=false，识别认证流后仍可能调用 openAppLink。SPEC 要求用户确认后打开，不能原样接入这一分支；普通、private、认证流均不得越过确认。
6. **文件选择与拍摄权限要分开。** 固定 FilePicker 会计算 capture/media 权限，普通 image/audio 请求可能触发额外权限流程。正常 SAF/Photo Picker 选择不应因拍摄权限拒绝而不可用；明确 capture 操作才请求对应系统权限。读取授权、MIME、多选和 App 私有目录过滤必须保留。
7. **通知站点许可不是系统许可或展示。** SitePermissionsFeature 的通知设置提示主要引导系统设置。需要真实 POST_NOTIFICATIONS gate，以及 Engine.registerWebNotificationDelegate 的展示/关闭桥接；不能用 Notification.permission 的值单独证明通知实际可见。

## 最小完整请求契约

- 每个请求保存原 `tabId + engineSession 身份 + requestId + origin + private`，回调前重新验证所有者仍有效。系统权限/文件结果不得重新查询当前 selected tab 后投递。切标签、导航、关标签、拒绝、取消、重建、迟到结果均须有一次且仅一次的终结和消费行为。
- camera、microphone、组合音视频、location 同时满足 Android runtime permission 与站点许可；近似位置正确处理；永久拒绝给系统设置入口。系统同意不能替代用户对网站的同意。
- 使用完整 origin 区分 scheme/host/port；站点提供允许、阻止、询问/重置。普通决策持久化；private 不继承或改写普通决策，不将 private 请求、URL、表单或 URI 保存到持久库或 saved-state；private 生命周期结束清理临时状态。
- 通知、自动播放、持久存储、媒体密钥、跨源存储及当前 Gecko 支持的本地网络权限具有明确策略；其他类型显式拒绝而不是悬挂。相应 Android 权限只按实际支持的系统版本声明、请求和验证。
- 文件支持 MIME、单选、多选、目录、取消及显式拍摄；返回 URI 具有有效读取权限，过滤 App 私有目录，临时上传及时清理。页面关闭或所有者失效时不能把文件交给另一页面。
- 页面 alert/confirm/prompt、认证、选项、日期颜色、beforeunload/repost 等完成确认/取消与请求消费；保存登录等未实现功能明确禁用，不能无响应地占住网页。
- 弹窗阻止后显示“已阻止弹出窗口 · 始终允许”，设置按站点保存且 private 隔离；允许后处理原请求及真实 window session。不能只设置全局 javaScriptCanOpenWindowsAutomatically=true。
- intent://、market://、weixin:// 等先显示真实目标应用与打开/取消；认证流同样确认。取消不启动应用；无处理器、应用卸载、安全 HTTP(S) fallback 和恶意 intent 有明确结果，不允许 component/selector/危险 flags 原样直通。
- 站点信息展示真实连接/证书、权限状态与修改、Cookie/本站数据清理、本站 uBO 开关；清理范围与 Gecko 实际 base-domain/origin 能力一致，不能在 UI 声称更窄范围。

## 建议批次与证据

1. 请求归属/生命周期基础；系统 camera、microphone、location；完整 origin 站点存储和站点信息/重置；同步补 B20 Room 清理。真实安全源 fixture 验证允许、拒绝、永久拒绝、组合权限、近似位置、重建、导航与 private 隔离。
2. 页面 prompts 与文件选择。真实 DOM input 验证单选、多选、MIME、取消、capture、返回读取、页面失效和临时上传清理；常用 JS/认证提示完成回调。
3. 通知真实展示、弹窗阻止/始终允许、新窗口，以及外部协议确认。验证普通和 private 两种模式、认证流不得直启、没有目标应用/取消/恶意 intent，以及系统权限撤回。

每批保留真实构建与设备日志并单独提交。上述准备不能替代功能实现、测试结果或最终逐项验收。
