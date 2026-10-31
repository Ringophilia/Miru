# P11 磁盘缓存时间区段：MP4公开索引预验

2026-10-31，API35/x86_64。这里只验证固定Media3 1.11.1公开样本表能力，**P11仍pending**；
没有使用播放器、解码器、GL、磁盘缓存或进度条。生产版本、依赖和主APK均保持0.5.11。

## 固定输入与真实终态

工作树为`C:\Users\tocml\.codex\worktrees\folder-upload\Miru`，输入基线`f825f6b`加本批测试。
证据保存在该工作树`artifacts/m3-cached-ranges/`，原始source ZIP、manifest、APK和每轮runner
输入分别固定，未将不同轮次拼成一次全通过。构建只使用PS7、显式JDK21/SDK与`--no-daemon`。

| 构建 | 真实终态 | 固定测试APK SHA-256 |
| --- | --- | --- |
| `build-01` | 成功，1分19秒 | `fd5378e67b1d41638e9ca97bc2c14c832608f2dc7f5fb52f3356d60e89a4254f` |
| `build-02` | 成功，19秒 | `fbc139a1ce136d36912bd05f705bd22ce3b6fe2b0437ffcf611b5b70c63bca36` |
| `build-03` | 成功，27秒 | `7b1865105ef95749f3d3a9f7d7d632e81096275662541e27d8c47c50c3d5f1c7` |

三轮主APK相同：474,353,265字节，SHA
`496f5b0ceb274de8bfa2ca14bc7b2dac1b09709b99ae5ff4ed321f53875b5634`。
build-03只加可选全表诊断，原断言不改；其后仅素材README补充结果，代码/视频/reference/生成器
与冻结输入一致，`source-review-final.json`逐项记录差异。不将提交后的素材README声称为原APK输入。

| 独立执行 | 终态 | 范围 |
| --- | --- | --- |
| `baseline-02` | **3/3通过，0跳过，0.166秒** | 原MP4头moov全120样本；无损尾moov搬移全部样本；省略数组不能建立索引的负例 |
| `complex-01` | **2通过/1失败，0跳过，0.281秒** | 强VBR/B帧头与尾moov通过；非identity edit的PTS比较失败 |
| `edit-diagnostic-01` | **1失败，0跳过，0.218秒** | 原edit断言仍失败，先记录全部265条真实公开表用于独立诊断 |

每轮adb均exit0；失败轮的宿主验证器正确exit1，不以adb正常退出代替测试通过。
新只读、无快照、no-audio的`emulator-5554`安装前两包不存在，未预授权限；安装后实际base.apk
SHA逐项核对。`baseline-01`因宿主预检错误要求空`pm path`必须exit0而停止，零测试；真实未安装
返回空输出/exit1。修正仅允许空输出且exit0/1，原预检失败保留。

## 独立素材与字节真值

`tools/export-cached-range-baseline.py`直接展开既有colors.mp4的stco/stsc/stsz/stts/stss及identity
edit，不调用Media3或FFmpeg；每样本固定offset/size/PTS/sync/payload SHA。原120样本有39种编码
大小，不能按字节比例推时间。尾moov负载保持不变，仅按实际物理位移重写chunk offset。

`tools/generate-cached-range-fixtures.ps1`生成自有红色/运动testsrc2/蓝色素材并用固定工具导出
逐包reference。头、尾各360包，实际观察252个B帧，编码包大小13–3500字节；两个同长2秒窗口
实际964/52204字节，超过54倍。头尾全部payload/size/PTS/DTS/duration/sync保持。edit素材265包，
包括8个负时间preroll/discard包；raw与应用edit的reference均保留，没有过滤样本去迎合库。

固定FFmpeg `n8.0.1-66-g27b8d1a017` SHA
`6d1169549f6a67de04dfc1d1868b514d594e365a1104fd384cbe851ded7108f8`，ffprobe SHA
`3ca44c072baeb38eaf21f32f8bf527718c5cd2ee0bc49122e187b7c1ab595644`。
generation-01在PowerShell对OrderedDictionary聚合时真实失败；partial产物、脚本和SHA保留。
generation-02只修显式数值投影，原VBR/B帧/edit阈值均保持，完整宿主检查通过后才冻结并构建。

## 编辑列表失败的准确含义

原失败为首样本：ffprobe期望`-266666us`，实际BoxParser `-250000us`。
独立展开该文件：movie timescale1000、media timescale15360，stts为265×512，首raw PTS1024，
elst media_time4864。因此直接edit公式是`(1024-4864)/15360=-0.25s`。

固定FFmpeg的mov_fix_index另将首个edit内呈现点归零。实际trace为
`Offset DTS by 2560 to make first pts zero`；首个edit内decode-order样本index5的raw PTS为5120。
根据原始stts/ctts/elst独立模拟该流程，全部265个ffprobe PTS精确复现。相对直接edit公式有统一
`256/15360s`位移，约16.7ms；负数微秒截断只解释显示为16666或16667us，不能解释位移本身。

最终诊断实际观察全部265项Media3公开表：offset/size/flags/payload SHA全相等，PTS全吻合独立
raw表加edit公式；与ffprobe的265项PTS均不同。原期望、包数、flags和PTS断言未改，无放宽容差，
所以原strict ffprobe比较门仍红；不据此断言任一库对所有edit都错误。

固定官方源码与实际trace的SHA在`diagnosis-01/primary-sources.json`：
[Media3 BoxParser 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/BoxParser.java)、
[FFmpeg精确commit](https://github.com/FFmpeg/FFmpeg/blob/27b8d1a017/libavformat/mov.c)。
全表差异在`edit-diagnostic-01/api-table-diff.json`，原runner/logcat及失败均保留。

## 重复执行与后续门

类：`app.miru.browser.capability.CachedRangeMp4IndexCapabilityTest`，必须显式
`-e miruCachedRangeCapability true`。基线精确选择headMoov/relocatedTailMoov/omittingSampleArrays
三个方法；复杂精确选择generated三个方法且追加`-e miruCachedRangeComplex true`。
诊断另加`-e miruCachedRangeTrace true`。完整方法名和参数数组在每轮`input.json`。
不带complex参数的整类有3个assumption skip，不能记成6/6；常规回归继续排除capability包。

生产映射必须跟随实际Media3呈现时轴，并另以独立时间真值、解码PTS/RGB、preroll/GOP、选中
audio、真实缓存空洞和停源seek交叉验证。还需HLS/DASH、fragmented MP4、其他VBR封装、
清理/淘汰/鉴权/切源失效、private零磁盘、offline独立存储及真实UI验证。
这些解析专项既不是磁盘可用性证明，也不提升P11或整个M3的验收状态。
