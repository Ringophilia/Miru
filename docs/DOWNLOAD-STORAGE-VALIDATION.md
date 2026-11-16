# 下载页存储条 D7

下载页按以下来源显示「下载已用」。

- 仍有记录的普通文件按 Android 内容提供方报告的当前大小统计。筛选、搜索及撤销期的临时隐藏不会改动读数。仅删记录后，保留的文件继续占用实际设备空间。提供方无法报告大小时，界面把合计标为「至少」。[Android 文件大小接口](https://developer.android.com/reference/android/provider/OpenableColumns#SIZE)
- 未完成的普通下载按临时文件已分配的磁盘块统计，预设的文件长度不会提前计入。离线视频按 Media3 已写入离线缓存的字节统计，正在写入的片段在提交后计入。[Linux 文件块计数](https://man7.org/linux/man-pages/man3/stat.3type.html)

「设备剩余」取应用数据所在文件系统对应用可用的空间。细条用已知的下载用量与该剩余空间计算比例，屏幕阅读器可取得完整数字文字。页面可见时本地和离线数据约每秒刷新，已保存文件在下载记录变化后重测，外部变化约每十秒复查。[Android 可用空间接口](https://developer.android.com/reference/android/os/StatFs#getAvailableBytes())

## 验证

- 固定构建 `download-storage-build-01` 编译通过，740 项源码输入无漂移。Lint 0 错误、29 警告。普通下载模块 10 项、离线下载模块 9 项 JVM 测试通过，零失败或跳过。
- API 35 x86_64 模拟器 `download-storage-device-01` 通过 4 项。实际分段下载创建了 16 MiB 稀疏临时文件，暂停后用已分配字节核对显示，取消后回到零。实际 MediaStore 文件用文件描述符核对大小，离线 HLS 用已存的分段核对合计。可访问性文字的剩余空间与 `StatFs` 读数一致，筛选和撤销窗口里的临时隐藏未减少合计。直接移除 MediaStore 文件、再清掉离线视频后，数字在当前页自动更新。原有普通下载删除撤销及混合下载筛选、重命名、撤销和播放用例同轮通过。
- 安装包大小和 SHA-256 已核对。临时 APK 副本及测试用户已清理，原模拟器用户已恢复。记录位于 `artifacts/m3-cached-ranges/download-storage-*/` 及 `artifacts/download-storage/`。真机、外置目录、无法返回文件大小的提供方及实际 TalkBack 朗读仍待验证。
