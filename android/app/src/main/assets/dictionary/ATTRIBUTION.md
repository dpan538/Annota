# Annota 完整离线词库

英汉：57,841 个 ECDICT 核心词条；汉英：125,301 个 CC-CEDICT 词条。
两份 SQLite 及其源数据、许可随安装包提供。查词只读本机，不上传输入、手写轨迹或文档。
英语支持精确、词形、前缀和轻微拼写候选；汉英支持简繁体、前缀和包含查询。
这是英汉 / 汉英词典，不是汉汉辞典，不支持完整句子翻译或在线发音。

## ECDICT

Linwei / skywind3000, ECDICT，项目许可 MIT。
来源：https://github.com/skywind3000/ECDICT
完整 MIT 许可在 licenses/ECDICT-MIT.txt；原始核心子集在 sources/ecdict-core.csv.gz。
Annota 对数据作核心词筛选、换行标准化及 SQLite 索引转换。
上游数据包含多种资料来源，公开分发前仍需复核来源链；仓库代码许可不等于所有外部材料的单独授权证明。

## CC-CEDICT

CC-CEDICT 社区维护，MDBG 发布；来源版本 2026-10-19。
来源：https://www.mdbg.net/chinese/dictionary?page=cc-cedict
许可：Creative Commons Attribution-ShareAlike 4.0 International（CC BY-SA 4.0）。
包括 Paul Andrew Denisowski 的 CEDICT 贡献，具体原始署名见 licenses/CC-CEDICT-header.txt。
完整许可离线附于 licenses/CC-BY-SA-4.0.txt；原始源数据见 sources/cedict.txt.gz。
Annota 仅转换为 SQLite 并增加索引；zh-en.sqlite 及转换结果沿用 CC BY-SA 4.0。
转换代码在 Annota Android 源码及 design/dictionary 构建程序中提供；不改变原释义。

## 字体与输入法

应用界面使用系统字体；随包指南嵌入 MiSans 与 Xiaolai 字体，许可另附。
系统手写使用设备已安装的输入法，不含在线识别 SDK。需在目标设备飞行模式验收。
