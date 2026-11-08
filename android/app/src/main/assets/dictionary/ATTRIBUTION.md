# Annota 离线词库

英汉：771,180 条记录，其中 942 条为 Annota 原创短语与学术术语；作者显式别名单独保留（630 条检索别名，
不再是按词条复制的索引行）。汉英：238,664 条记录；125,301 条来自 CC-CEDICT，971 条为原创短释义的反向
等价表达，3,529 条来自开放中文词网，108,863 条为下方英文词义索引的中文等价表达反向生成的汉英条目
（wordnet-reverse / wiktionary-reverse，单条最多保留 4 个英文等价词）。另有 4,148 个繁→简单字映射
用于检索扩展。
英中词义：72,948 个英文词头、95,221 条词义关联、50,639 个同义词集，由 WordNet 3.0、Chinese Open
WordNet、Chinese WordNet 与 OMW Wiktionary 中文词网合并；英文释义经 CILI 对齐采用 Open English WordNet 2025。
数据、来源和许可随安装包提供。查词只读本机，不上传输入、手写轨迹或文档。

支持完整词组、显式词形、保留边界的连字符变体、前缀和轻微拼写建议；繁体会自动扩展为简体检索。
词库随 APK 安装和更新，无需另外下载。提供单词与词组查询；未整体收录的英文长词组和短句可使用随包
模型生成独立的离线参考译文。词条检索、词形处理、词组匹配与局部语境简释由 Annota 在设备内完成。

## Annota 原创词组与术语

Copyright (c) 2026 Dai Pan and Annota contributors. MIT License.
完整许可：licenses/Annota-phrases-MIT.txt。
原创源文件：sources/annota-phrases.tsv；编辑范围与转换说明：sources/provenance.json。
涵盖常用搭配、论文表达、Attention / 机器学习、统计、软件及科学工程术语。
中文释义为项目原创编辑的简短等价表达；没有复制外部词典的长解释。
源文件可独立按 MIT 使用。带“……”的句式不会自动生成反向词条。

## ECDICT

Linwei / skywind3000, ECDICT，项目许可 MIT。
来源：https://github.com/skywind3000/ECDICT
完整许可：licenses/ECDICT-MIT.txt；APK 内完整中文释义记录：sources/ecdict-core.csv（仓库为 .csv.gz）。
Annota 保留全部有中文释义的 770,238 条记录，进行换行标准化及 SQLite 索引转换，不按词频或考试标签筛选。
保留上游 BNC/COCA 词频秩（bnc/frq）用于候选排序；词频不影响词条是否收录。
上游包含多种资料来源；仓库代码许可不等于所有外部材料的单独授权证明。

## CC-CEDICT

CC-CEDICT 社区维护，MDBG 发布；来源版本 2026-10-19。
来源：https://www.mdbg.net/chinese/dictionary?page=cc-cedict
许可：Creative Commons Attribution-ShareAlike 4.0 International（CC BY-SA 4.0）。
包括 Paul Andrew Denisowski 的贡献，原始署名见 licenses/CC-CEDICT-header.txt。
完整许可：licenses/CC-BY-SA-4.0.txt；APK 内原始源数据：sources/cedict.txt（仓库为 .txt.gz）。
原始释义保持不变；zh-en.sqlite 的格式转换和数据组合按 CC BY-SA 4.0 分发。

## 字体与输入法

界面和指南所用字体许可另附。系统手写使用设备已安装的输入法，其网络行为由输入法管理。

## Annota 缩写与首字母索引

Copyright (c) 2026 Dai Pan and Annota contributors. MIT License（完整许可：licenses/Annota-phrases-MIT.txt）。
原创源文件：sources/annota-abbreviations.tsv。共 128 条经人工编写的缩写/数字缩合条目
（CAP、RAM、CI、k8s、i18n、a11y、l10n、LLM、RAG 等），每条含全称、中文短义、领域与上下文线索词；
中文释义为项目原创编辑，没有复制外部词典解释。另有 401,909 条多词词条的首字母签名（initials 列）
仅用于生成候选展开，不新增词条、不改变原有释义。
缩写识别在设备内完成，不联网；未收录缩写只有在上下文命中候选词时才显示展开，否则回退普通查词。

## 索引精简

英汉检索的边界等价（连字符/空格）由 `entries.boundary_key` 列与索引提供，不再为每个词条复制
`aliases` 行；`aliases` 只保存作者短语的显式别名。该调整不删除任何词条，词条数仍为 771,180，
用于首个 1.2.x 候选构建；数据库文件以 gzip `.pack` 随 APK 提供，首次启动解压一次。

## Princeton WordNet 3.0

Princeton University，WordNet 3.0。
完整许可：licenses/WordNet-3.0.txt、licenses/wordnet-3.0-license.txt。
来源：https://wordnet.princeton.edu/
Annota 使用 `data.noun/verb/adj/adv` 的同义词集、英文释义与词形，以及 `index.sense` 的义项序号
（按词频排列的义项顺序）；`cntlist` 未使用。词形与义项序经 `sources-build/wn30-*.tsv.gz` 审核。

## WordNet / Chinese Open WordNet + 中文词网合并

Chinese Open WordNet（COW），Copyright (c) 2013, 2014 Francis Bond and Shan Wang；
Chinese WordNet（CWN，Academia Sinica，台湾）与 OMW Wiktionary Mandarin 中文词网，
均为开放词典数据，经 PWN30 synset 与 CILI 对齐后合并。
完整许可：licenses/Chinese-Open-WordNet.txt、licenses/cwn-Academia-Sinica-license.txt、
licenses/omw-wikt-CC-BY-SA-3.0-NOTICE.md、licenses/cili-CC-BY-4.0.txt。
来源：https://github.com/omwn/omw-data（wns/cow、wns/cwn、wns/wikt）与
https://github.com/globalwordnet/cili 。
随包 sources/wordnet-cow.json 保留 COW 原始数据；`sources-build/` 中的 OMW 中文字表、CILI 映射与
WordNet 3.0 派生文件随仓库提交、不进入 APK，可离线重建。合并后的 wordnet.sqlite 提供独立只读索引，
保留英文释义、义项顺序及不规则词形；展示时去除中文分词标记“+”。
结果为 72,948 个英文词头、95,221 条词义关联、50,639 个同义词集，与主词库存在重叠，不计为同等数量的新增单词。

## Open English WordNet 2025

Open English WordNet contributors，CC BY 4.0。
来源：https://github.com/globalwordnet/english-wordnet （2025 edition，WN-LMF 1.3）。
完整许可：licenses/oewn-CC-BY-4.0.txt；对齐映射的许可：licenses/cili-CC-BY-4.0.txt。
Annota 仅使用可经 CILI 对齐到 PWN30 的同义词集英文释义与同义词，中文等价仍来自 COW/CWN/Wiktionary；
未对齐的 OEWN 新同义词集不进入索引，避免丢失中文或伪造对应关系。

## English Wiktionary / Kaikki

English Wiktionary contributors，CC BY-SA 4.0。提取自 https://kaikki.org/dictionary/English/ 。
独立 wiktionary.sqlite 包含 46,333 个词头、59,290 个义项，和主词库存在重叠。
仅使用带普通话译词且有义项说明的记录，筛除古旧/罕见标记，建立词形和词头索引。
上游有时把翻译放在 “Translations” 等章节标题下而不是义项释义下；此类标题不是词义，
本版在构建时排除（1,786 条），不再作为义项参与排序。
来源哈希及转换说明：sources/kaikki-provenance.json；详细署名：licenses/Wiktionary-NOTICE.md。
另提取完整英文数据中的明确词形关系，以及无汉译词头的实质英文释义；运行时优先中文，英文补充明确标注，
不冒充中文翻译。中文等价表达还用于生成汉英反向条目（`wiktionary-reverse`，与 WordNet 中文义项合并、
每词最多 4 个英文等价词），这些派生行同样按 CC BY-SA 4.0 分发。两份新增源数据与转换哈希：
sources/kaikki-forms-provenance.json、sources/kaikki-en-definitions-provenance.json。
CC BY-SA 4.0 许可文本已随包提供。词条作者与历史可通过 Wiktionary 同名英文条目查阅。

## OpenCC 繁简映射

Open Chinese Convert（BYVoid 与贡献者），Apache License 2.0。
来源：https://github.com/BYVoid/OpenCC
许可：licenses/opencc-Apache-2.0.txt；署名说明：licenses/opencc-NOTICE.md。
仅取用 `TSCharacters.txt` 的繁→简单字映射（4,148 条），构建为 zh-en.sqlite 的 variants 表；
未在运行时链接 OpenCC 库，未修改映射内容。

## LemmInflect

Bradley J. Ascob, LemmInflect，MIT。https://github.com/bjascob/LemmInflect
固定提交 b7699808106a4ce843fc7f0e8e5d87fcb84cc636，导入查找表与上游 overrides，仅使用显式非自身词形候选。
上游词形资料源自 NIH SPECIALIST Lexicon；原始资料条款：https://lhncbc.nlm.nih.gov/LSG/Projects/lexicon/current/web/termsAndConditions.html 。
来源：sources/lemminflect-provenance.json；完整 MIT 许可：licenses/LemmInflect-MIT.txt。

## 离线参考翻译

OPUS-MT / University of Helsinki，Xenova ONNX 量化转换，Microsoft ONNX Runtime。
模型、分词器、推理均在设备本地，无运行时下载，遥测关闭。
完整许可和模型来源另见应用关于页面“开源与词库许可”中的离线翻译段落；译文属于机器生成参考，不能视为已校验词典释义。

## 构建与检索

词库由仓库内 `design/dictionary/build.py` 从 `sources/` 与 `sources-build/` 离线重建；构建检查来源哈希、
重复词条与边界规则，并生成检索用 Bloom 位图。运行时检索使用只读 SQLite 连接、内容缓存与位图预筛，
不联网、不下载。仓库保留上述来源的压缩文件；Android 打包后 APK 中对应材料去掉 .gz 扩展名，
解压后的内容相同。`sources-build/` 只用于构建，不随 APK 分发。
