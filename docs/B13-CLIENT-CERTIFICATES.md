# B13 客户端证书选择首批（2026-11-05）

## 实现

网站发起真实 TLS 客户端证书请求时，Miru 显示请求主机和当前页面，用户可以取消，或进入 Android 系统证书选择器。传入引擎提供的可接受签发机构、RSA/EC 类型、请求主机和空的预选别名；所选别名仅交回原始原生请求，应用不另行记住。无匹配证书或取消时交回空结果，系统选择器无法打开时给出不同提示。

选择器只允许一个请求在途。系统回调切回 Main；页面失效后，迟到结果不能启动或完成新页面的验证。应用重建不重复打开已在途的选择器。新增选择数据、请求标记和提示状态仅在内存，完成事件不携带主机或别名。无痕界面说明系统选择器及其授权由 Android 管理，选择的证书身份仍会由网站获知。

接口与依赖内 Android Components 158.0.2 的 `CertificatePicker` 实现核对，使用 [Android KeyChain API](https://developer.android.com/reference/android/security/KeyChain)。Android 在缺少匹配证书时可能不显示选择器而直接回调空结果；本批没有安装测试身份、读取私钥或修改信任设置。

## 验证

- 两项相关 JVM 测试通过，无跳过，检查重复 / 错误 / 迟到结果不能释放或消耗另一个在途选择器。记录在 `artifacts/client-certificates/compile-jvm-01.log` 和 `jvm/`。
- 最终固定构建 `client-certificate-build-03` 成功，705 项源码清单无漂移，Lint 0 错误 / 29 警告。主 APK 476,125,649 字节，SHA-256 `651972c2052d6c5749ef4b04b10f6f931d20c26a4c48ca35b4ab589e4647e1be`；测试 APK SHA-256 `60561f08f244a16e5bc393f1858777a5fd5caf757bdcb60f57ea8680c15c98e9`。构建脚本的播放器 90 项是未改输入的既有 JVM 报告。
- API 35 x86_64 模拟器，用 [badssl 的客户端证书测试站点](https://badssl.com/)产生实际 TLS 挑战，三个方法分别在新应用进程运行，均 **1/1 通过**：
  - `client-cert-cancel-final-01`：浏览器内取消真实请求，旧操作失效；JUnit 29.138 秒、运行器 35.024 秒。
  - `client-cert-picker-final-01`：实际系统选择器出现，注入真实返回按键取消，Android 正常回调空结果，完成原请求；JUnit 31.142 秒、运行器 37.139 秒。现场记录 `systemPickerShown=true selected=false unavailable=false`。
  - `client-cert-private-final-01`：关闭发起请求的无痕标签后，旧标记无法再启动选择器；JUnit 34.097 秒、运行器 41.348 秒。
- 直接安装固定快照；安装后临时拉回核对 SHA-256 / 大小并删除临时副本。没有注入证书、别名或原生完成回调，也没有选择设备上已有的用户身份。

首轮 `client-certificate-device-01` 同进程整类执行为 **2/3 通过**，JUnit 65.720 秒，不能计整类通过。第二次访问同一主机没有再次收到挑战而超时；保留原失败。单独重启进程后原系统选择器方法 1/1 通过，随后把该类单列到 capability，并按最终固定包分别运行三个方法。TLS 会话可能复用先前的无证书决定，完整常规回归不能直接混跑这些前置条件；运行说明见 `VALIDATION-ARTIFACTS.md`。

## 首批范围

设备上未准备匹配的测试身份，因此选中有效证书、实际成功完成 mTLS、各类企业签发机构及真实手机仍待验证。Android / 引擎自身的身份授权与 TLS 会话缓存不等于 Miru 新增的存储，完整私密审计仍未完成。本批仅完成请求与选择接口及取消链路首批，不把成功认证或整个 B13 记为验收通过。

没有跑完整常规回归、ARM64 打包或升版本号。本站数据清理继续按维护者选择留在 `codex/site-data-wip`；P11/P2 冻结和第一组已有三项回归失败保持。历史 APK 可能按保留策略裁剪，日志、清单、哈希及清理台账继续保留。

## 2026-11-11 · 实际选择证书并认证

在独立 API 35 x86_64 模拟器临时用户中运行。测试从 BadSSL 官方站点通过常规 HTTPS 下载公开的 PKCS#12 测试身份，使用 Android 证书安装器将其安装为「VPN 和应用用户证书」，名称为 `Miru BadSSL QA`。身份随临时用户移除。主机用同一公开身份访问测试站点得到 HTTP 200，空证书请求得到 HTTP 400。证书有效期、指纹和对照页面保存在 `artifacts/client-certificates/positive-auth/`。[BadSSL 证书下载](https://badssl.com/download/)

只在英语界面的全新临时 Android 用户中选下列方法。测试操作 Android 安装器和 KeyChain 选择器的实际控件，检查回调属于原标签，并检查原标签加载的安全 HTTPS 页面和服务端正文。应用测试包不打包证书，系统信任配置沿用设备默认值。

```powershell
./tools/run-device-validation.ps1 -App -BuildName $FeatureBuildName `
    -RunName client-certificate-auth-check -UserId $TestUserId `
    -Selector 'app.miru.browser.capability.ClientCertificateIntegrationTest#selectingThePublicTestIdentityCompletesClientAuthentication' `
    -InstrumentationArguments @{miruClientCertificateTest='badssl'} -Expected 1
```

前三次未完成认证，原失败均保留在 `artifacts/m3-cached-ranges/client-certificate-auth-device-01` 至 `client-certificate-auth-device-03`。首轮安装器多了一步用途选择；第二轮证书已安装且系统找到了匹配签发者，测试未点中证书行；第三轮点中证书行，测试未匹配系统的全大写确认按钮。

最终固定快照 `client-certificate-auth-build-04` 编译成功，711 项输入无漂移，Lint 0 错误、29 警告。主 APK SHA-256 `7afbbc8c9bb000d7b59107f560d8a254856d0c09ca507813ae76f9e68f7b3372` 与摄像头权限批次相同；测试 APK SHA-256 为 `3a9abf50d2ca6c439453ad66fe06d5ede5eea5466db893ae3f20ee8efbcc24f7`。引擎 JVM 任务为 `UP-TO-DATE`，沿用相同输入下 133 项通过、1 项跳过的报告。

`client-certificate-auth-device-04` 通过 1/1，JUnit 26.749 秒，运行器 37.986 秒。真实 KeyChain 记录选中 `Miru BadSSL QA`，Miru 收到原标签的选择结果，该标签实际加载安全的 `client.badssl.com` 成功页及服务端正文。固定 APK 安装哈希和大小均通过，拉回核对的临时副本已删除。已移除测试用户 14 并恢复原用户 0。本批证据限于一个公开 RSA 测试身份和 API 35 模拟器。真机、企业签发机构与其他证书类型留待验证。
