# 第三方组件与授权范围

本说明的原始依赖清单对应 2026-10-22 的 M0 源码快照；下表另记 M1 新接入组件。Miru 自有代码使用 MPL-2.0，不会改变依赖、工具或图形素材的原有权利与条款。

| 组件 | 当前版本 / 来源 | 许可与说明 |
| --- | --- | --- |
| Mozilla Reference Browser 参考代码 | `61267efe132591f6f1dfd58185ebd8dbff6aa816` | MPL-2.0；保留 `BrowserController.kt` 文件头，来源见 [UPSTREAM.md](../docs/UPSTREAM.md) |
| Mozilla Android Components | `158.0.2` | MPL-2.0；[许可原文](mozilla/MPL-2.0.txt) |
| GeckoView | `158.0.20261021001945` | POM 声明 MPL-2.0；内部第三方代码仍保留各自许可 |
| Mozilla application-services | `158.0.1` | MPL-2.0 及内部 Rust/C/C++ 第三方条款；[源码](https://github.com/mozilla/application-services)，POM 的子组件条款在下方清单中保留 |
| AndroidX / Compose / Kotlin / kotlinx.coroutines | 实际解析版本见清单 | 主要为 Apache-2.0；个别 POM 缺少许可字段，不能仅据此认定完整合规 |
| JNA | `5.18.1` | [Apache-2.0 或 LGPL-2.1-or-later 双许可](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE)；本项目选择 Apache-2.0 选项，须保留其原有第三方声明 |
| Google Play services FIDO | `21.3.1` | 以及 base `18.7.2`、basement `18.9.0`、tasks `18.3.2`，POM 声明 [Android SDK 专有许可](https://developer.android.com/studio/terms)；不是 MPL/Apache 授权 |
| JUnit / Hamcrest | `4.13.2` / `1.3` | JUnit 声明 [EPL-1.0](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt)，Hamcrest 另有 BSD 条款；当前 release 解析图中也出现了测试支持组件，不能仅按“测试依赖”忽略 |
| Gradle Wrapper | `9.3.1` | Apache-2.0；[许可原文](gradle/Apache-2.0.txt)及[说明](gradle/NOTICE.txt)，不重新授权为 MPL |
| uBlock Origin | `1.75.0`，官方 AMO 签名 XPI，未修改 | GPL-3.0；[来源与 SHA-256](ublock/NOTICE.md)、[许可全文](ublock/LICENSE.txt)、[固定版本源码](https://github.com/gorhill/uBlock/tree/1.75.0) |
| OkHttp / Okio | OkHttp `4.12.0`，Okio 为其传递依赖 | Apache-2.0；[OkHttp 许可](https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt) |
| AndroidX Room / DocumentFile | Room `2.8.5`、DocumentFile `1.1.0` | Apache-2.0；Room 编译器与 KSP 仅用于构建 |
| AndroidX Media3 | `1.11.1`，ExoPlayer / HLS / DASH / OkHttp datasource / session / UI | Apache-2.0；[对应版本源码](https://github.com/androidx/media/tree/1.11.1)、[许可](https://github.com/androidx/media/blob/1.11.1/LICENSE)；未引入 IMA、Cast 或 FFmpeg 扩展 |

Android Components 与 GeckoView 的当前 POM 均将源码指向 Mozilla release 修订：

- [e4ce606692cd81e6898547e2edd7c552fefcbb04](https://hg.mozilla.org/releases/mozilla-release/rev/e4ce606692cd81e6898547e2edd7c552fefcbb04)
- [该修订的源码归档](https://hg.mozilla.org/releases/mozilla-release/archive/e4ce606692cd81e6898547e2edd7c552fefcbb04.tar.gz)

应用级适配源码位于本仓库 `browser/engine/`；本次未修改或内嵌 Mozilla 库本身的源码。发布二进制时，仍须确保接收者能够取得实际使用版本的 MPL 对应源码及修改，而不是只提供上游最新分支链接。Gecko 与 application-services 内部的第三方声明也须随实际发布产物核对。

## 解析结果与检查边界

[dependency-license-inventory.json](dependency-license-inventory.json) 记录 Gradle `:app:releaseRuntimeClasspath` 解析出的 **253 个外部组件**，含平台约束和多平台元数据节点，不等于 APK 中实际保留的 253 个库。字段来自本机 Gradle 缓存的对应 POM：坐标、声明的许可名称及链接、SCM 来源，以及是否找到 POM。

该清单是依赖元数据快照，不是完整 SBOM 或完整的第三方版权告知文件。POM 中的多项许可不一定代表可以任选，缺少许可字段也不意味着没有许可证；`full-megazord` 的 POM 同时列出其大量子组件许可，其中部分链接的名称与目标不一致，发布时应以对应版本源码的实际许可文件为准。

自有源码可以独立公开。包含 Google Play services 的构建产物不能宣称依赖全部为自由开源组件，也不能将这些库重新授权为 MPL。当前仓库不提交依赖 AAR、APK、AAB 或 Gradle 缓存。

本次仅公开源码，不发布二进制。日后发布 APK/AAB 前，须按实际产物补齐嵌套库的许可全文、版权声明及必要的源码获取方式，确认 Google 条款的适用范围，并复查有无被裁剪或遗漏的告知。

## 后续功能

M1 已在 assets 中加入未修改的 uBO 官方签名 XPI；尚未接入 Anime4K 或 AMD FSR。M3 清晰度探测按产品规范第 8.9 / 13 节，参考本地 Ringo-Cove 的 `lib/core/resolution.dart` / `hls_probe.dart` 思路重新实现 Kotlin 解析器，没有复制 Dart 源文件或引入 Flutter / Dio / pointycastle 依赖；密码运算使用平台 `javax.crypto`。所参考的两个 Dart 文件无独立许可文件头，本地 Cove 根目录亦无 LICENSE；这里记录规范指定的参考来源，没有将其标记为第三方 Apache / MIT 代码。超分着色器接入时仍需保留各自许可与署名。

M0 的 253 项依赖 JSON 尚未重做 M1 / M2 / M3 完整清单，不应用于描述新版的全部解析组件。公开发布 APK/AAB 前应按实际产物更新清单与告知，并复查 uBO 集成的 GPL 义务；不能仅凭独立扩展形式预先认定宿主必然属于简单聚合。

主要依据：[Mozilla MPL FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/)、[Apache-2.0 正文](https://www.apache.org/licenses/LICENSE-2.0)、[GNU 对聚合与组合程序的解释](https://www.gnu.org/licenses/gpl-faq.html#MereAggregation)。
