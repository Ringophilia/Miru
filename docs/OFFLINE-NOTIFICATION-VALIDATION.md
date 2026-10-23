# 离线视频通知与传输取消专项

日期：2026-10-23（Asia/Shanghai）。本页只覆盖 `:feature:offline` 的通知、通知动作和活动 HTTP 请求取消，不代表整个 M3 或真机后台矩阵已验收。

## 源码实现

- 通知的暂停、继续、取消使用显式、不可变的广播 PendingIntent，进入 `OfflineCoordinator` 与下载页共用的状态机；广播异步结果保留到原生命令提交完成。单条动作身份包含完整 UUID 和命令，不使用截断哈希。
- 接受取消后保存 UUID 取消标记，清除认证上下文和该 UUID 的离线缓存；迟到的原生完成回调、旧继续动作不能重新发布完成通知或恢复已取消任务。全局取消只针对活动任务，不修改历史完成副本。
- 前台服务通知只表示当前传输。它不再担任暂停、失败、完成通知的分组摘要，避免最终传输结束时 Android 连带删除这些持久结果。
- 每条持久通知以完整 UUID 为 tag，内容 Intent 的 data 也包含完整 UUID；保留应用主入口的 action、category 及 `CLEAR_TOP` / `SINGLE_TOP` 行为，避免哈希碰撞覆盖通知或修改另一条通知的回放 UUID。
- 每次原生下载调用持有独立 `OfflineHttpCalls`，按下载 UUID 登记。暂停、取消、删除及 `Downloader.cancel()` 会取消该 UUID 的实际 OkHttp Call，包括已开始读取的响应体；已取消作用域不能启动后续分片。响应体结束或失败后移除 Call，调用结束后清理作用域，原生重试重新创建作用域。不同 UUID 的请求互不取消。
- Cookie / Authorization 仍仅存进程内存；跨来源重定向仍移除这两类头，HTTPS 不允许降级。新增诊断只保存通知 ID、系统原因、标志和分组，不保存媒体 URL、请求头或数据库副本。

## 失败证据与修复依据

首轮 `artifacts/m3-quality/initial-device.log` 两项通知专项都失败。权限及通知频道正常；系统 events 证明失败、完成通知均已发布，随后 FGS 8040 被移除，Android 以原因 12（`REASON_GROUP_SUMMARY_CANCELED`）立即删除对应子通知。筛选证据为 `offline-notification-system-before.log`。这是分组生命周期错误，不能靠增加等待时间解决。

去除临时摘要后，`fixes-device.log` 的认证失败通知取消项通过。第二项已完成初始下载及通知持久性检查，但暂停活动任务后，FGS 在 10 秒窗口内仍未退出。系统 events 显示暂停通知先发出，8040 继续每秒更新，直到测试清理释放服务端挂起的响应体后才移除；证据为 `offline-notification-socket-before.log`。因此补充显式关闭 Call，而未延长通知等待或 HTTP 超时。初始完成下载的 FGS 已经正常退出，之后才新建挂起任务；本次并非历史活动下载维持服务。测试清理后只读查询离线索引为空，系统服务列表也为空。

## 自动测试与当前结果

`OfflineHttpCallsTest` 两项使用实际回环 TCP socket：

1. 两个作用域分别读取已发出首字节、剩余响应体挂起的 HTTP 请求。取消 A 后，A 必须在 3 秒内以 IO 错误返回，B 保持活动并在服务端释放后正常完成；客户端正常读超时仍是 20 秒。
2. 已取消作用域新建的下一条 Call 已处于取消状态，不能开始下一分片或认证重试请求。

上述两项 JVM 测试已通过，XML 记录在 `feature/offline/build/test-results/testDebugUnitTest/TEST-app.miru.offline.OfflineHttpCallsTest.xml`，本次执行总计 0.318 秒。结果只能证明这两项请求生命周期测试，不能代表整次应用构建或设备回归通过。

`OfflineNotificationIntegrationTest` 使用真实 `NotificationManager.activeNotifications` 与系统发布的 PendingIntent，未替换为伪造管理器或直接调用协调器模拟通知点击：

1. 准备使用有效合成认证头，传输使用过期令牌并实际收到 403；验证登录提示通知在 FGS 退出后仍在。发送其取消动作后，缓存为零、无法播放、通知消失；再次发送保留的旧继续动作，不得新增 HTTP 请求或恢复状态。
2. 实际完成 160×90 HLS，关闭来源服务；新任务在响应体挂起期间，通过前台通知暂停，保持缓存，等待 FGS 在原有 10 秒窗口内退出，且未主动释放来源。通过暂停通知继续后，再通过前台通知全局取消；历史完成副本及其通知仍在。最后发送历史完成通知的真实内容 Intent，验证 UUID 到 MainActivity、播放器的完整路由、真实尺寸与播放进度，并播至末段，来源请求数保持不变。
3. 使用固定合法 v4 UUID `e0ccdb2e-ff63-4c76-8de0-9247fd821c0d` 与 `70412c11-52a8-4d37-8e3d-d3cea4232125`，两者 Java hash 均为 893219024。测试通过封装的一处反射调用实际内部 publisher，发布两条合成元数据完成通知；验证两条系统通知同时存在、tag 为各自完整 UUID、内容 PendingIntent 不相等，取消其中一条不删除另一条。该项不创建下载记录，不代替第 2 项实际回放验证，也没有新增生产调试入口。

显式 Call 取消及 UUID 身份修复后的 `notification-identity-device.log` 三项结果为 2 通过、1 失败。认证取消和固定哈希碰撞项通过；实际回放项唯一报告的错误位于规则清理 `ActivityScenario.close()`，规则仍记录 PAUSED，而系统 lifecycle events 显示同一 MainActivity 已实际销毁。主 Activity 正确通过 `onNewIntent` 保存了带完整 UUID data 的真实通知 Intent；[AndroidX ActivityScenario](https://github.com/android/android-test/blob/main/core/java/androidx/test/core/app/ActivityScenario.java) 按最初 Intent.data 筛选后续生命周期，导致测试跟踪失配。

本次只在测试 finally 中恢复规则的原始 MainActivity Intent，再结束播放器，以便规则观察恢复和销毁；真实通知点击、完整 UUID 身份及全片播放断言均保持，并新增已收到完整 UUID data 的断言。该清理修正后的三项设备重跑仍由根代理统一执行，重跑前不将本专项标为全通过。生命周期筛选系统证据为 `offline-notification-scenario-before.log`。前序完整设备日志也包含超分专项独立失败，不能作为本专项结果。

## 验证边界

最终 `notification-cleanup-build.log` 记录构建成功（34 秒）；`artifacts/m3-quality/notification-cleanup-device.log` 终态为 **3/3 通过，27.387 秒**。显式 socket 取消、持久结果通知、真实完成通知全片播放与固定 UUID 碰撞隔离均通过上述断言；先前失败日志继续保留。该终态取代上文“重跑前”的待验证状态，不扩大为其他设备或完整回归的通过。

设备专项针对通知权限已允许的 API 35 隔离 x86_64 模拟器，依赖受控的回环 HTTP 合成媒体，不代表 ARM64 真机、系统重启、通知权限拒绝、厂商后台限制或真实网站认证轮换通过。最终构建、版本、提交和完整回归结果应以主验证报告及对应最新日志为准；初始失败日志保留。
