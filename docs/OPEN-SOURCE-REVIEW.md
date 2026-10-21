# 公开源码前的许可与来源检查

检查日期：2026-10-22（Asia/Shanghai）。开发者：Ringophilia 与 xiaomao。

## 结论与许可证选择

目前检查没有发现会直接阻止 Miru 自有源码采用 MPL-2.0 公开的明确代码许可冲突。这个结论有范围限制：它不是对所有知识产权、商标、专利或全部传递依赖的法律无侵权保证，也不是 APK/AAB 的完整分发审查。

选择 **MPL-2.0** 作为自有代码、构建脚本和文档的默认许可证。理由是已有 Mozilla MPL 参考代码及核心依赖，采用同一许可证便于保留文件级义务；允许商用和与其他许可的独立文件组合，分发修改后的受许可源码文件或对应二进制时须履行源码公开及告知义务。依据为 [Mozilla 官方 FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/)与仓库 [LICENSE](../LICENSE)。这不意味着全部独立依赖都被重新授权为 MPL。

署名在 README、AUTHORS.md、NOTICE 和中英文“关于”页中明确为 **Ringophilia 与 xiaomao 共同开发**。第三方原有署名不替换为项目开发者，Mozilla 的现有文件头和 Gradle Wrapper 的 Apache 声明保留。

## 已核对的证据

- 检查本次修改前已跟踪的 104 个文件、唯一的初始提交，以及当前源码与构建配置。原提交作者已是 `ringophilia <ringophilia@gmail.com>`，未改写历史或将第三方贡献冒认为项目作者；`.mailmap` 统一显示为 Ringophilia。
- `BrowserController.kt` 明确声明 Mozilla 参考来源并保留 MPL 文件头；`docs/UPSTREAM.md` 记录固定上游提交。核对了该提交的上游文件头及许可证。
- Gradle 实际解析 release 依赖图，得到 253 个外部组件（含约束和元数据节点）；POM 声明快照见 [依赖清单](../third_party/dependency-license-inventory.json)。这不等于已逐一审查所有二进制内嵌组件。
- 发现 Google Play services FIDO 等四项专有条款组件，以及 JNA 双许可、JUnit EPL 与 Mozilla 原生库的多种第三方许可；具体说明见 [第三方记录](../third_party/README.md)。主项目 MPL 授权不覆盖它们。
- 未发现已跟踪的 uBO XPI、播放器/超分移植代码或发行 APK/AAB；规范中对未来 uBO 集成不受 GPL 影响的绝对断言已改为按实际集成复查。
- 对已跟踪源码及 Git 历史进行了常见令牌、私钥、带凭据 URL 与敏感文件名的模式扫描。唯一命中是 `AddressResolverTest.kt` 中明确用于测试拒绝 user-info URL 的假数据。未发现真实凭据；该模式检查不等同于穷尽式秘密检测。
- `local.properties`、`keystore.properties`、密钥库、构建目录、日志与 `artifacts/` 均被 Git 忽略。发布不包含这些本地文件。

## 图形素材的剩余问题

维护者说明，苹果眼睛原图是以《Ringo EXPO 08》封面为灵感，由 AI 生成，并未原样照搬元素。已查看当前原图与描摹脚本，记录了 [来源说明](../design/PROVENANCE.md)，但没有开展专业的实质相似性、商标检索或生成服务条款审查。

AI 生成、描摹为矢量、声明“致敬”或标注无关联，均不足以单独排除第三方权利问题。为避免无依据地承诺美术素材的再授权，本次将图形素材及其派生应用图标排除在项目 MPL 授权范围外；这项许可边界本身也不会消除潜在侵权。仓库因此是开放源码项目，但不能宣称所有美术素材都可自由再分发。

## 二进制发布前仍需完成

1. 按实际 APK/AAB 的依赖与原生嵌套组件补全许可全文、版权声明及准确源码地址，复核依赖元数据缺项和多许可的实际含义。
2. 复查 Google Play services 的适用与分发条款；如希望提供完全自由开源的构建，先评估移除或替代这些依赖的功能影响。
3. 保存图标的生成过程和服务条款；如需更稳妥的品牌权利及完全开放的美术素材，另行设计并明确授权。
4. 将来加入 uBO、过滤列表、媒体库或超分着色器时重新检查，不把当前 M0 的结论扩展到尚未实现的版本。

## 本次验证

使用 JDK 21 运行 `:core:common:test :browser:engine:testDebugUnitTest :app:lintDebug :app:assembleDebug`，Gradle 构建成功；8 项 JVM/引擎单元测试通过，Lint 为 0 错误、13 项警告。本次没有运行设备仪器测试或 release 构建，不将结果解释为完整运行时或发布验收。

调试 APK 的 ZIP 清单确认包含 MPL 与 Apache 许可原文、Mozilla 声明、共同开发者的 `Miru-NOTICE.txt`，以及不再被排除的 `META-INF/AL2.0`、`META-INF/LGPL2.1`。这仅验证这些已知告知的打包情况，不代表所有依赖告知均已完整。APK 只用于本地验证，不上传到本次源码仓库。

新加 XML 资源与依赖 JSON 解析通过，文档本地链接检查通过，暂存修改的空白检查通过。
