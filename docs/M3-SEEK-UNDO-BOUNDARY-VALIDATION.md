# 0.5.20 单视频内部 seek 与下载撤销焦点

2026-11-03，基于8c212f7，版本0.5.20/code28。完整目标继续，尚无本版完整常规或Release终态。

## 单视频边界

`mp4-video-boundary-before-01`新增三项真实冷启动，原包2过/1失败。完整缓存在3.950秒首个
视频输出为4.000秒绿帧；删除原文件并移除字节8960后，新player报错2005、没有输出帧，
但旧单视频索引仍发布[0,4)秒。此负例与两项末帧正例的JSON/runner/logcat均保留。
两项尾部测试分别用单视频和AV源在11.950秒冷准备，实际首个视频PTS11900000、YUV蓝帧。
它们证明已知视频末帧的EOS例外，不证明尾部AAC精确输出。

普通无重排单视频改用已验证的native时间单元/读取闭包，同AV一样包含内部seek所需的下一
可输出样本。移除8960后的前缀变为[0,3.900001)，补回后恢复[0,12)。原单字节9.2秒负例的
前缀相应为[0,9.100001)。原单视频重排分支暂保留，仍待独立decoder-output闭包，未借此验收。

## 混合下载撤销

原0.5.19完整151项为150过/1失败。完整buffer补取显示删除后447ms进行了触摸点击，但未产生
Snackbar action或任何后端undo调用，两个timer最终6秒到期。同包隔离随后1/1不抵消此失败。
时间区间日志还显示，键盘在点击前开始重新弹出，并在点击后完成显示；主界面imePadding随之
移动Snackbar。触点可能因此离开动画中的按钮，这是依据时序的推断，不将隔离通过当作根因证明。

实际APK固定ui-test1.12.1、Material3 1.4.0。官方固定源码证实Android performClick使用触摸，
Snackbar action会恢复当前continuation；源码URL/SHA和两条时序链在`mp4-av-undo-observation-01`。
来源：[ui-test 1.12.1](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.12.1/ui-test-android-1.12.1-sources.jar)、[Material3 1.4.0](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0-sources.jar)。

新增多选后搜索框不得仍聚焦的断言，旧包`download-undo-focus-before-01` **0/1**，确实
Focused=true。下载页现在在多选、请求删除和实际删除时clearFocus，避免确认框关闭后恢复
搜索输入。没有改变两个后端的6秒删除期限、Snackbar期限或原3秒撤销检查。

## 固定新输入与验证

`seek-undo-fix-build-01`冻结633项输入，无源码漂移，JVM90/90，lint0错误/27警告。

| APK | 字节 | SHA-256 |
| --- | ---: | --- |
| main | 474484397 | `8a35ec749c1bc64bad898f56fb2dcd03fc75ac9b2832de830a46d2b20beacc90` |
| player-test | 30360251 | `a36663a07cdc87084f5b23f1e49e0ef4bda65597383e1f47079549cbb40396f8` |
| app-test | 12352979 | `e60246751f5d00cc1abb19de0b5ce377d096970ab360857f4bd66e98cf25f300` |

- 同次`seek-undo-fix-module-01`完整模块 **114/114，JUnit81.009秒，0跳过**，含三项边界探针。
- 同包`seek-undo-fix-ui-01` **9/9，JUnit67.117秒**，含MP4八项和混合下载完整真实操作。
  多选已失焦，实际触摸撤销后两个pending清除，记录保持completed，之后停源离线播放通过。
  新时序显示UI undo_completed在234ms，测试观察after_undo_click时两类pending均false。
- 精确时间段、9秒PTS/RGB、晚于memory的120秒画布、同player清metadata等原门保持；
  JSON、PNG、哈希和新日志已归档。APK按固定快照安装，没有逐轮二进制副本。

本版`seek-undo-fix-routine-01`完整常规 **151/151，JUnit1262.152秒，0失败/跳过**，runner1268.598秒，ADB0及终态OK。固定四个独立前置类与capability包仍排除，不算通过。整组中的混合撤销通过；旧151失败及新专项保留独立输入。新R8/ARM64运行、正式签名、
精确AAC preroll/EOS、重排/复杂edit/其他容器、长片内存/取消、完整权限/资源/鉴权矩阵、
物理机与M0–M5其他条目继续保留，不以本批范围替代全规范。
