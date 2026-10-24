# 仅清表单时保留私密 Cookie 的核实

2026-10-24 的源码复核、已复现失败与修复。本文不把静态推断或尚未重跑的修复计为设备通过。

## 当前应用与既有覆盖

原 `BrowserController.resetFormData` 先以不可恢复方式移除所有标签，再按原 ID、URL、私密属性重新添加。
`BrowserDataCleaner` 允许仅选 `FORM_DATA`，此时不请求 `COOKIES` 删除。
本地固定 `browser-engine-gecko:158.0.2` sources 中，`GeckoEngineSession.close` 第 972–975 行确实调用
`GeckoSession.close`；对应 GeckoView Java API 说明会释放 Gecko 对象并卸载页面。

既有 `BrowserCoreIntegrationTest.siteStorageAndFormsAreActuallyRemovedWhileTabAddressesAndCookiesRemain`
以真实页面证明表单、旧导航栈被清空，保留的 Cookie 属于普通 jar。独立 Cookie 删除测试覆盖私密 jar
的显式删除，但两者均不能证明仅清表单会保留私密 jar。

## 上游机制证据与版本边界

实际 AAR 的 `AppConstants.sys.mjs` 记录版本 `158.0.2`、build ID `20261021001945`、HG revision
[`e4ce606692cd81e6898547e2edd7c552fefcbb04`](https://hg.mozilla.org/releases/mozilla-release/rev/e4ce606692cd81e6898547e2edd7c552fefcbb04)。
本次该 revision 的两个 C++ 文件 HG 下载均遇到传输失败，不能声称已核对这两个文件在该精确修订中的内容。

可获取的官方 `FIREFOX_158_0_RELEASE` 标签有以下明确机制。这是 **158.0 标签的证据**，不是与实际
158.0.2 修订逐字相同的证明：

- [`CookieService.cpp`](https://raw.githubusercontent.com/mozilla-firefox/firefox/refs/tags/FIREFOX_158_0_RELEASE/netwerk/cookie/CookieService.cpp)
  第 260 行注册 `last-pb-context-exited` 观察者，第 333–338 行收到通知后移除
  `privateBrowsingId=1` 的 Cookie 并重建 `mPrivateStorage`。
- [`CanonicalBrowsingContext.cpp`](https://raw.githubusercontent.com/mozilla-firefox/firefox/refs/tags/FIREFOX_158_0_RELEASE/docshell/base/CanonicalBrowsingContext.cpp)
  第 88–124 行按全局私密 context 计数发出通知（受 private autostart 条件约束）；第 1787–1795 行将
  attached/discarded 的私密内容 context 接入该计数。第 2230–2242 行在进程切换时临时加计数，明确防止
  暂时降零结束私密会话。因而“最终会重新创建私密标签”本身不足以保证 Cookie 连续保留。

本次获取文件的 SHA-256，便于复核时识别原文：

| 官方 158.0 标签文件 | SHA-256 |
| --- | --- |
| `netwerk/cookie/CookieService.cpp` | `d5eec111822d21c1e7f0100e045e165185ec96401a3abbca193b963ffed27561` |
| `docshell/base/CanonicalBrowsingContext.cpp` | `d5041a53c77ab5569d8f8bf42c03d92ad812a4dac8e4caa2bf60bdf3a55dd60f` |

## 真实失败与保留的夹具

`PrivateFormCookieIntegrationTest.clearingFormsFromANormalTabRetainsThePrivateCookieAfterAFreshPageLoad`：

1. 先关闭测试环境旧私密标签，只保留一个新私密标签，避免另一私密 context 掩盖最后一个 context 退出。
2. 本机 HTTP `/seed` 唯一一次发送随机 `HttpOnly` Cookie；随后导航到不再发送 `Set-Cookie` 的 `/private`，
   服务端检查真实请求 Cookie。另查普通 jar 没有该 Cookie。
3. 切换到普通标签，仅选择全部时间的 `FORM_DATA`，核对成功集合仅包含该类型，选择、私密 ID 与 URL 保留。
4. 切回私密标签，必须产生清理后的实际 HTTP 请求、新编号页面标题且加载结束，才判断 Cookie 保留。
   此等待不能由旧 Java 快照、复制的旧标题或立即查询旧 Cookie 满足。
5. 对随后 600ms 的六次 Cookie 查询重复断言：私密 Cookie 存在，普通 jar 不含该 Cookie。

该夹具只验证清理范围的私密 Cookie 连续性；既有真实输入框夹具继续承担表单删除验证。
root 运行的 `artifacts/browser-core/image-policy-device.log` 记录了该专项真实失败：
19 项中本项在“清理后的私密文档应携带原 Cookie，且不重种”的断言失败，整个运行 149.113 秒。
测试已等到清理后的新编号文档与真实 HTTP 请求，因此这证明原重建方案实际丢掉私密 Cookie，
不是立即读取旧快照造成的推测。该批另外三个来源策略失败由各自专项处理，本文不将其计为表单故障。

## 已实现修复（等待统一构建和设备重跑）

`FormSessionReplacement.kt` 通过 `engine.createSession(private = true, contextId = …)` 创建私密替代
session，保留 desktop mode。固定 AC 158 源码会忽略初始 `about:blank` 的位置及加载回调，故先加载
无脚本、无网络的空白 `data:text/html` 文档，并等待该文档随机标题标记与真实开始 / 完成回调。
等待期间旧 sessions 均保留；不是只构造 Java 对象、读取 `isOpen` 或任意延时。

所有准备及替换共用原 10 秒期限。真正移除旧 sessions 前，在 AC Store 同一把同步锁内复核标签
ID、顺序、URL、私密属性、context ID、desktop mode、parent ID、原 engine 引用及选择。
准备期间新增标签、跳转或改变选择会在旧 session 仍存在时安全失败，不用旧快照覆盖新标签。

替换时使用支持已存在 engine 的 `AddTabAction`；`AddMultipleTabsAction` 会拒绝非空 engine 及 parent ID。
预先验证 parent 图，父标签先添加后恢复原显示顺序与选择。注入 session 后显式以
`LOAD_FLAGS_REPLACE_HISTORY` 加载原 URL，避免准备文档成为 Back 条目。
没有 engine 的私密 NTP 保持纯原生；已保留旧 engine 的私密 NTP 才保留空白替代 context，且不加载
`about:newtab`。表单旧备份、Undo、缩略图仍清理，写入继续使用 `sessionWriteLock`。

准备失败、取消、超时或状态变化时关闭未接入的替代 sessions；已在标签中接入的 session 交给正常标签
生命周期管理。不会复制、保存、恢复或改变 Cookie，亦不留独立的永久 guard。

新增第二项 `aNewTabDuringNativePreparationAbortsBeforeRemovingAnyOriginalTab`：在 Main 上以
UNDISPATCHED 协程启动清理到 native 等待点，同一回调内新增普通标签，断言清理安全失败、所有旧 ID
及新 ID / 选择保留、旧私密文档没有重新请求、原 Cookie 仍存在。无需睡眠或生产测试钩子制造竞态。
两项专项及既有真实表单 / 恢复测试均待 root 统一重跑，不能提前报告修复通过。

## 2026-10-24 设备重跑

`artifacts/browser-core/core-final-v2-build.log`：Debug、AndroidTest 与 Android JVM 测试构建成功，77 秒。
`core-final-v2-device.log`：API35 x86_64 **20/20，通过，136.888 秒**，其中两个 PrivateForm 专项均通过，
并同时通过原有真实输入框清理、Cookie 分界/全部时间清理与图片/工具栏测试。
第一项直接证明仅清表单后新的私密 HTTP 请求仍携带原 HttpOnly Cookie，且普通 jar 未被污染；
第二项证明准备期间新增标签会在移除原标签前安全中止。
这不证明表单清理后强停恢复、所有 parent 图/标签顺序或物理设备矩阵均已完成。
