# B13 受保护内容权限（2026-11-11）

网页发出 GeckoView 媒体密钥访问请求时，Miru 显示「播放受保护内容」权限面板。用户可允许或阻止，也可从地址栏站点信息修改选择或重置为询问。普通选择按来源和浏览上下文保存。无痕选择保存在会话内存中，关闭全部无痕标签后清除。面板说明网站和许可证服务可能识别播放设备。

权限处理沿用当前文档归属检查。切走原请求页面后，旧答复无法继续授权。站点选择修改和重置会同步已有的原生权限记录。数据库升级保留旧字段和选择。升级前已被引擎记录为阻止的网站，可以在站点信息里改为允许或重置。接口依据 [GeckoView 站点权限文档](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/permissions.html)。

## 验证

- 引擎 JVM 测试共 132 项，131 项通过，1 项因 Windows 无法创建测试符号链接而跳过。报告在 `artifacts/protected-media/jvm/`。
- `protected-media-build-01` 编译成功，707 项输入源码清单无漂移。Debug Lint 为 0 错误、29 警告。2026-11-11 恢复时重新核对全部源码路径和哈希，与原固定构建一致。核对记录为 `artifacts/protected-media/resumed-source-check.json`。
- 主 APK 476,126,673 字节，SHA-256 `231b0830973e2f9cc1c1604dbaf82312fd2f66d5b035ee98ae047fbc6fd562b9`。测试 APK SHA-256 `37219c929338a35a69f7e88c85fda9bde92af1fcbae95c937431ca98a402fe58`。
- 2026-11-05 的 `protected-media-device-01` 在 API 35 x86_64 模拟器 3/3 通过，JUnit 81.604 秒、运行器 91.156 秒。用本地页面发起实际 `com.widevine.alpha` 请求，验证拒绝、面板改为允许、重新询问、无痕隔离、最后无痕关闭和旧请求失效。允许后实际创建 `MediaKeys` 并绑定到网页视频元素。安装后的 APK 哈希及大小比对通过，临时副本已删除。

## 待验证

需要用实际加密媒体完成许可证请求和播放测试，并在真机核对网站兼容性。切换到原生播放器的 DRM 防护沿用现有实现，其实际加密媒体回归仍待验证。测试结果仅覆盖网站授权及网页 MediaKeys 初始化。恢复本站数据清理 WIP 时，应先基于当前 main 合并权限数据库升级，再使用同一测试资料。
