# uBO 首装生命周期：迟到回调修复

日期：2026-10-23。此变更修复安装超时后的状态调和，不等同于首装网络稳定性、冷启动零漏拦或 d3ward 验收通过。

## 已确认的问题

历史原始日志 `artifacts/m1-021/install-state-logcat.txt` 中，240 秒等待超时之后仍收到 genuine `onReady`。固定 Gecko 源码的安装路径在等待 manifest / blocklist 之后才注册取消监听，因此应用超时取消不保证原生安装已经停止。旧逻辑会一直保留 FAILED，并可能允许再发起一次重叠安装。

`latest-install-logcat.txt` 中，MLBF attachment 下载失败后约 22 ms 安装返回，约 200 ms 后收到真实就绪；这支持 blocklist 下载是首装耗时的一部分。Android 固定版本未打包该官方 dump，首次依赖 Remote Settings。未关闭 blocklist、签名、TLS 或更新检查。

## 本次实现与证据

- 240 秒 watchdog 只更新用户可见的失败状态；原生操作槽保留到真实结果或 genuine `onReady`，重试不能叠加尚未结束的安装。
- 迟到就绪可继续应用私密权限和最新的 enabled 设置；仅后续 setup 真正成功才清理错误 / READY。设置切换由同一顺序 driver 处理。
- 旧 attempt 的错误、重复就绪、禁用期间的迟到回调和私密授权失败有明确状态规则，不使用扩展描述 / options URL 猜测就绪。
- `UblockInstallStateTest` **8 / 8 通过**，对应 `artifacts/m3-offline/next-stage-unit.log` 与 engine JUnit XML；固定 AC API 编译通过。

本批尚未重新跑真实原生迟到回调、完整首装网络 / 无网络 / 规则初始化矩阵。0.4.1 离线的 47 项设备回归使用修复前的应用 APK，不能作为本变更设备验收证据。

## 后续

继续评估原样打包同 revision 官方 MLBF dump 的构建资源方案，并验证来源 / SHA / 长度、打包内容、离线首装、更新路径及许可证。当前仅临时目录调查，没有将 dump 写入 APK，也没有改变任何安全偏好。官方初始 dump 机制见 [Mozilla Remote Settings](https://firefox-source-docs.mozilla.org/services/settings/index.html#initial-data)。
