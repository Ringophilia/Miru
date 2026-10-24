# 浏览器核心批次提交前只读审查

审查日期：2026-10-24。审查对象为当前 B10 / B11 / B20 未提交改动、
`BROWSER-CORE-VALIDATION.md`、相关生产源与设备测试源。最初审查仅只读；其后 root
另行分配了本问题的实现与测试源码，详见下节。此代理没有运行 Gradle、设备或提交。
以下是源码证据，不是新的运行结果。

## 需要修正的具体问题

### P2：缺失的原始 Referer 被重新合成为页面来源

触发条件：页面或图片采用 `Referrer-Policy: no-referrer`，用户长按一张没有
DetectedMedia 捕获记录的 HTTP 图片并复制 / 分享。

- `BrowserController.contextHeaders` 第 351–359 行在没有捕获记录时调用
  `headersForMediaUrl`；后者第 406–410 行只有 User-Agent / Cookie，没有真实图请求的
  Referer 或文档 / 元素 referrer policy。
- 入口将缺失的 Referer 以 `null` 传入 `contextReferrer`。该函数把 `null` 解释为
  默认策略，生成同源完整文档地址（含 query）或跨源 origin。
- `ContextImageLoader` 第 51、66 行把这份新合成的头发到原生图片请求。
  它第 62 行的 `.orEmpty()` 仅防止后续重定向再扩大来源，不能撤销首次请求已经
  合成的来源。网页要求不发送来源时，这次操作仍会发送来源信息。

建议保持没有捕获到的 Referer 缺失，或取得原始 owning document / image 的实际策略
再构造头。修复后应使用两个真实来源：提供 no-referrer 页面和图片的服务器分别记录
初始 Gecko 请求及随后原生复制请求，证明两者都不发送 Referer；普通有来源策略与
跨源重定向另保留覆盖。当前 `ContextReferrerTest` 的缺失来源断言只覆盖显式空字符串
和重定向调用，不能证明上述初始入口正确。

### 后续修复实现（运行证据仍由 root 补记）

修复没有把所有图片的来源一律删除。`ContextImagePolicy` 实现八种标准策略、可信来源
降级、默认端口、去凭证 / fragment、超长来源退为 origin；只有从真实当前图片取得的
空策略才使用默认 strict-origin-when-cross-origin。无法查询的 `null` 不是默认策略。
依据为 [Referrer Policy 算法](https://w3c.github.io/webappsec-referrer-policy/#determine-requests-referrer)。

独立 `image-policy.js` 在 document_start 观察 meta，既有媒体 / 长按倍速脚本仍在
document_idle。背景层从真实 main_frame / sub_frame 响应捕获 HTTP 策略，并用文档 nonce
绑定；不会把内容脚本中不可用的 `document.referrerPolicy` 属性当成已取得 HTTP 策略。
可信 pointer / contextmenu 事件记录实际图片的 WeakRef 和 press nonce，背景以可信
MessageSender 的 tab / frame 路由精准询问，避免相同 src 的不同图片或 iframe 被任选。
匹配使用 currentSrc，图片自身策略优先，来源为实际 frame 文档。Native 侧将随机请求 ID、
文档 / 连接 nonce、sender URL、session 和顶层当前页面共同验证，不比较临时 Port wrapper
身份。新鲜 relay 优先；超时的旧连接被退休并尝试新连接，全部查询有 3.5 秒预算。

新增 9 项纯策略测试源码和 6 项真实设备测试源码。设备夹具包含私密 HTTP no-referrer 的
Gecko 首次 / Native 复制两次请求及同 URL 重载、meta 覆盖 HTTP、相同 src 不同图片策略、
iframe 实际来源、SPA 同文档基线保留，以及无法确定的 meta 中间变更不得产生额外请求。
成功复制都校验 PNG 原始字节，操作由真实触摸打开菜单并点击复制。它们尚须 root 实际运行，
源码存在不计为通过。

异步 MutationObserver 无法可靠还原同一同步任务内的全部中间 meta 值。遇快速新增后移除、
连续写入有效 / 无效策略等无法证明的状态，本实现明确返回能力不可用；不能回退到旧 HTTP
策略。图片若有明确自身策略仍可使用。该边界需保留在最终验收说明，不能宣称所有动态 meta
路径已完整支持。纯策略 JavaScript 语法检查及 git diff --check 通过，不替代设备日志。

## 其余审查结论与证据边界

这次静态审查暂未发现第二个足以阻止该批提交的具体错误。特别检查了清理时间范围的
类型限制、Cookie 普通 / private 两次完成后才成功、表单重建与保存锁、菜单文档归属、
跨源图片重定向认证剥离、图片 provider 的只读授权与容量限制。该表述不证明这些路径
已在全部环境验收。

文档中的 84 项 JVM 与 12 项设备专项属于已有日志范围。完整设备回归、ARM64
release / lint 与最终 APK 摘要由 root 独立执行和记录。不能用本审查、10 项来源策略
单测或 12 项浏览器 / 图片专项，将全部 B10 / B11 / B20 或整份规格标成完成。

尚未闭合的 blob 原文档图片动作、跨应用接收、各类缓存 / 权限 / HTTP 认证直接删除、
表单清理后强停恢复和物理设备矩阵继续以验收矩阵为准；本审查没有把这些已记录的
未完成工作另包装成新发现。
