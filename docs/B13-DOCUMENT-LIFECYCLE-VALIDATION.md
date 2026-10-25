# B13 顶层文档生命周期事件（0.5.6基础）

固定AC158.0.2/Gecko158.0.20261021001945的公开NavigationDelegate没有same-document标志，
URL比较不能区分同地址reload和hash/history导航。上传文件需要按实际文档保留，不能在SPA导航后提前删除。

## 精确资源修改

原始`modules/GeckoViewNavigation.sys.mjs` 22,791字节，SHA-256
`fb5926acbf81a405929309b39076f1ef86c4e407795f8903963d3f15158f208c`；
对应修改源码23,179字节，SHA-256
`a5e17b2c1b6253dd531aa69a9f9319f4f090831b2c5b9f9a4c814fdd9b5cb598`。
原onLocationChange新增接收第四个aFlags，仅顶层在原LocationChange发送前增加
`Miru:DocumentLocation`，唯一payload为原生SAME_DOCUMENT标志的Boolean。
不修改原导航消息、允许/阻断决策、权限、安全首选项或HTTP请求。
原/改MPL源码和出处保留在`third_party/mozilla-permission-policy/`。

固定Messaging sender是`sendRequest(type, payload)`，不是单对象参数。
没有Java监听器的popup/inert session只丢弃新消息并记录事件名警告；固定C++ dispatcher忽略
Java的无listener返回值并返回NS_OK，不阻断下一条原消息。这是源码结论，不能代替popup设备回归。

## 已取得证据

`artifacts/b13-upload-lifecycle/gecko-navigation-20261026-011500/retry-1/verification-summary.json`
与同目录command.txt、日志保存完整命令和固定原AAR/omni pins：

- Java/完整归档检查59项；实际权限模块JS22项、实际导航模块JS54项通过。
- 原4079项路径和压缩方法保持，4077项字节原样、GEO与导航两项精确修改、5项官方blocklist新增；总4084项。
- 原`defaults/settings/last_modified.json`保持，SHA-256
  `56ff287f7e6e41e30c0a766ed3073e8d2752aca8784960f9001a4f324d6c8f0d`。
- 两次生成结果逐字节一致，新omni16,459,282字节，SHA-256
  `f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`。
- 首次JS异常测试桩误把权限URI也设为抛错；固定原模块同样失败。只修桩为实际location URI后重跑全部检查，
  原失败日志保留父目录，没有因此改变生产补丁。

`056-first-omni-verification.json`另验证首轮0.5.6 Debug APK确实包含上述完整资源。
该APK SHA为`ab9e7ca427c62a1b9e20374f9a03005edddcca2959958d61eb422e82993d572f`，
它是首轮开发快照，不是最终发布包或完整上传验收通过声明。

## 验收边界

这些检查证明资源修改范围、源码契约、归档保持与初轮APK打包；不证明整个B13、完整网站兼容或正式发行。
Java内部ABI接入、文件实际交付、取消/私密/系统选择器生命周期及旧提示回归，需要对应APK设备证据。
0.5.5的98/98历史与已验证ARM64包保留，不把它套给新增上传源码。
