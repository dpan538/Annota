# 1.0.3-test Beta 手写与查词验收记录

记录日期：2026-11-01。版本：`1.0.3-test` Beta / versionCode `14`，GitHub Pre-release。稳定版与稳定更新通道仍为 `1.0.2` / `13`。本记录区分实现范围、自动化结果、模型实际运行和目标设备体验；历史版本的验收不作为本轮新增能力的测试结果。

本轮测试环境为专用 Android 16 / API 36 ARM64 模拟器，硬件标识 `ranchu`。尚未进行 Xiaomi Pad 9 Pro Max 的实际手写笔、笔感与翻译模型下载验收。最终非调试 Beta 的 93 项 Android 回归与 12 项 Python 词库回归全部通过；附件公开下载验证另记于发布记录。目标平板按[测试清单](BETA-1.0.3-TEST-CHECKLIST.md)记录结果。

## 实现范围

| 范围 | 当前行为 | 主要实现与回归入口 |
| --- | --- | --- |
| 现有笔种 | 速度自适应去抖，平滑压力与速度响应，连续变宽轮廓；铅笔颗粒由笔迹坐标确定，重复绘制不重新随机生成 | [InkGeometry](../android/app/src/main/java/io/github/dpan538/annota/InkGeometry.kt)、[InkQualityTest](../android/app/src/androidTest/java/io/github/dpan538/annota/InkQualityTest.kt) |
| 笔迹兼容与导出 | 新笔迹保留采样时间、原始压力和笔刷版本；旧笔迹继续旧版绘制。屏幕与 PDF 使用同一派生几何；单点使用居中的圆形外观 | [Store](../android/app/src/main/java/io/github/dpan538/annota/Store.kt)、[PdfEngine](../android/app/src/main/java/io/github/dpan538/annota/PdfEngine.kt)、InkQualityTest |
| 局部擦除 | 沿橡皮完整扫过路径切割，补充线段交点；擦后保留未擦部分的已计算位置与宽度，避免重新去抖改变剩余笔迹 | [InkEditing](../android/app/src/main/java/io/github/dpan538/annota/InkEditing.kt)、[InkEditingTest](../android/app/src/androidTest/java/io/github/dpan538/annota/InkEditingTest.kt) |
| 相关修复 | 签名板由笔接管时移除掌触杂线，笔抬起后不把残留掌触接成笔迹；竖长图片统一按比例缩放；无原始 PDF 的空白笔记可导出 | [SignaturePad](../android/app/src/main/java/io/github/dpan538/annota/SignaturePad.kt)、[MainActivity](../android/app/src/main/java/io/github/dpan538/annota/MainActivity.kt)、PdfEngine |
| 离线词组 | 新增 942 条原创短释义、682 个显式词形 / 拼写别名；保留词边界，使用局部最长且不冲突的搭配；不将 compact 模糊结果作为精确释义 | [词库构建与来源](../design/dictionary/README.md)、[Dictionary](../android/app/src/main/java/io/github/dpan538/annota/Dictionary.kt)、[DictionaryContextTest](../android/app/src/androidTest/java/io/github/dpan538/annota/DictionaryContextTest.kt) |
| 可选句子翻译 | 复用查词面板；完整保留选区，最多 4,000 字符。缺少模型时由用户确认经 Wi-Fi 下载，之后在设备上处理英汉文本 | [SentenceTranslation](../android/app/src/main/java/io/github/dpan538/annota/SentenceTranslation.kt)、[界面状态回归](../android/app/src/androidTest/java/io/github/dpan538/annota/SentenceTranslationTest.kt)、[实际模型探测](../android/app/src/androidTest/java/io/github/dpan538/annota/SentenceTranslationDeviceTest.kt) |

这里的阻尼与质感是输入轨迹和视觉笔形的处理，不改变玻璃与笔尖的物理摩擦；没有启用厂商专有笔身触觉、自动字形重绘、识别后替换字体、整字对齐或重新排版。合成轨迹的滤波、缓存耗时和导出几何测试不能证明真实笔尖到墨迹的延迟或用户主观笔感。

## 已取得的证据

| 检查 | 已观察结果 | 原始记录 |
| --- | --- | --- |
| Python 词库回归 | `python3 design/dictionary/test_lookup.py`：12 项通过；逐条覆盖新增词组和别名、词边界、反向术语、跨行断词、缩写标点、长输入不截断及数据库完整性 | [测试源码](../design/dictionary/test_lookup.py)；构建数量与 SHA-256 见 [manifest](../design/dictionary/bundle/manifest.json) |
| 词库资产一致性 | 构建目录与 Android assets 中两份数据库的 SHA-256 均与 manifest 一致，schema 为 3 | [Android manifest](../android/app/src/main/assets/dictionary/manifest.json) |
| 首次下载及真实推理 | 测试开始时模型不存在，下载后实际执行英译汉与汉译英，记录 `passed: true` | [下载结果 JSON](../android/verification/handwriting-1.0.3-test/translation-download.json)、[测试输出](../android/verification/handwriting-1.0.3-test/translation-download.txt) |
| 断网真实推理 | 模型已存在；网络快照记录 `Active default network: none`，仍完成双向推理和两条阅读样本，记录 `passed: true` | [断网结果 JSON](../android/verification/handwriting-1.0.3-test/translation-offline.json)、[网络快照](../android/verification/handwriting-1.0.3-test/translation-offline-network.txt)、[测试输出](../android/verification/handwriting-1.0.3-test/translation-offline.txt) |
| 历史版本覆盖候选版 | 已公开的非调试 1.0.2 创建旧格式笔迹和书签后，经 `adb install -r` 升级为同证书、非调试的 1.0.3-test Beta；书库、源文件和状态文件校验值、书签、设置与初始化标记快照一致 | [升级基线 JSON](../android/verification/handwriting-1.0.3-test/upgrade-baseline.json) |

升级基线使用两份内置文档及一条旧格式笔迹和书签的测试书库，证明该夹具在非调试 1.0.2 → 非调试 Beta 覆盖安装前后保持一致；不代表所有用户数据组合，也不代表 Xiaomi 系统安装器实测。早期内部候选记录保留在 `upgrade-internal-history.json`。

Android 回归覆盖笔迹版本与原始采样、慢速抖动与快速轨迹、固定颗粒、透明笔画接缝、绘制缓存、矢量导出、橡皮扫掠与插值时间、掌触接管、查词语境、模型下载授权及过期回调隔离。执行记录在 [本轮验证目录](../android/verification/handwriting-1.0.3-test/)；最终非调试包的 [93 项完整回归](../android/verification/handwriting-1.0.3-test/beta-regression.txt) 全部通过（94.517 秒）；早期失败记录保留用于追溯。

## 翻译质量与联网边界

真实模型探测验证了下载和端侧推理链路，未将输出流畅或 `passed: true` 当作专业术语正确率证明。已观察到的样本：

| 原文 | 实际输出 | 判断 |
| --- | --- | --- |
| `The model does not use recurrent layers.` | `该模型不使用反复间隔。` | 否定保留，但 `recurrent layers` 错译，不能用于确认模型结构 |
| `Each query attends to all keys in the input sequence.` | `每个查询都参加输入序列中的所有键。` | `attends` 按普通“参加”理解，丢失注意力机制含义 |
| `The training set contains 100 examples, while the test set contains 20.` | `训练集包含100个示例，而测试集包含20个。` | 本条数量与数据集区分保留；不推断其他句子的质量 |

随包短语库优先提供已编写的简短术语释义；句子模型是通用机器翻译，术语、否定、数量和关系仍需对照原文。没有增加远程大模型回退，也不会在缺少模型时显示伪造译文。

普通查词不初始化翻译 SDK。用户进入翻译功能后，Google ML Kit 可能下载模型、取得更新 / 兼容性信息并发送 API 使用与性能指标；输入和译文在设备处理。模型首次下载不保证在所有设备或中国大陆网络上成功。归属标识、失败重试与联网边界见[翻译说明](TRANSLATION.md)和[隐私说明](PRIVACY.md)。

## 最终记录

- 最终 Beta：93 项 Android 回归全部通过，12 项 Python 词库回归通过；Debug、测试包、Lint 与非调试 Release 构建通过。Beta 标识的中英文横竖屏、稳定 / 测试更新通道隔离也在回归内。
- 已发布 [1.0.3-test Beta](https://github.com/dpan538/Annota/releases/tag/v1.0.3-test)，源代码提交为 `8ef3da1f5f3508baf77c6272cd39dbd8ed170c14`。公开 APK、SHA256SUMS 与更新清单均已匿名重新下载，字节数、SHA-256 和版本一致；GitHub 标记为 Pre-release，稳定通道仍为 1.0.2。
- 公开下载的非调试 APK 已在专用模拟器重新覆盖安装、冷启动；安装前后测试书库快照一致，界面显示 `1.0.3-test · Beta`。固定签名证书与 1.0.2 一致。完整包身份、校验值和下载结果见[发布验证 JSON](../android/verification/handwriting-1.0.3-test/release.json)，启动后的界面见[Beta 标识截图](../android/verification/handwriting-1.0.3-test/beta-library.png)。
- Xiaomi Pad 9 Pro Max 真机：仍待实际笔型号、中文小字、英文连写、钩折 / 公式、模型下载和断网翻译验收。此前版本的真机通过记录不覆盖本轮变化。

调研与未实施方案保留在[研究记录](HANDWRITING-LOOKUP-RESEARCH-2026-11-01.md)；研究中的字形整理、厂商预测 / 触觉能力没有作为本版已实现功能。
