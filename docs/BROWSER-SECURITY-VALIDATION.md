# 浏览器隐私与安全设置 B21

设置作用于 Gecko 网页浏览，普通和无痕标签共用。阻止第三方 Cookie 默认开启。关闭后 Gecko 仍拦截已知跟踪器的 Cookie。仅 HTTPS 默认关闭，开启后按 Gecko 的规则升级网页连接。本机和部分局域网地址按 Gecko 的例外规则处理。[Gecko 连接升级规则](https://searchfox.org/mozilla-central/source/dom/security/nsHTTPSOnlyUtils.cpp)

安全 DNS 默认使用 Gecko 的自动保护模式。它根据地区、网络条件和系统策略判断是否使用 DoH。关闭会禁用 Gecko 的 DoH，自定义模式需要填写有效的 HTTPS 服务地址。自定义地址在切换模式后仍会保留，供下次选用。[Gecko 安全 DNS 设置接口](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.html)

## 验证

- `browser-security-build-02` 编译通过，固定的 732 项源码输入无漂移。Lint 0 错误、29 警告。设置数据 JVM 5 项通过，引擎 JVM 133 项通过、1 项因 Windows 符号链接能力跳过。两次固定构建的主 APK 一致。JVM 报告在 `artifacts/browser-security/`。
- API 35 x86_64 模拟器 `browser-security-device-02` 通过 3 项专项。HTTPS 验证先收到 HTTP 页面，开启后观察到 TLS 连接且未收到目标页面的明文 GET，关闭后重新收到正常 HTTP 页面。测试通过 `sslip.io` 把域名解析到环回地址，网页请求由本地测试服务接收。首轮的 `.localhost` 测试只观察到 Gecko 的预连接升级日志，服务端未收到 TLS，保留了失败记录。[测试域名服务说明](https://nip.io/)
- Cookie 测试在同一站点请求上读到 Cookie，也在另一站点嵌入该站点时观测到默认阻止、关掉开关后恢复发送。DoH 测试验证了自动、关闭和自定义的设置界面，拒绝保存 HTTP 解析地址，重建界面后仍为自定义模式，并在所选端点观察到实际 TLS 握手请求。有效证书下的 DNS 查询和应答、自动模式的地区和网络行为、真机仍待验证。
- 两轮安装均核对 APK 大小和 SHA-256，测试后删除临时 APK 副本，恢复原 Android 用户并移除临时用户。输入、日志和结果保存在 `artifacts/m3-cached-ranges/browser-security-*/`。本批未跑完整常规回归或重建 ARM64 包。
