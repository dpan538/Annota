# Annota 离线词库

英汉：771,180 条记录，其中 942 条为 Annota 原创短语与学术术语；682 个显式词形 / 拼写别名另计。
汉英：126,272 条记录，其中 125,301 条来自 CC-CEDICT，其余为原创短释义的反向等价表达。
数据、来源和许可随安装包提供。查词只读本机，不上传输入、手写轨迹或文档。

支持完整词组、显式词形、保留边界的连字符变体、前缀和轻微拼写建议。
词库随 APK 安装和更新，无需另外下载。提供单词与词组查询；未整体收录的英文长词组和短句可使用随包模型生成独立的离线参考译文。
词条检索、词形处理、词组匹配与局部语境简释由 Annota 在设备内完成。

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
完整许可：licenses/ECDICT-MIT.txt；完整中文释义记录：sources/ecdict-core.csv。
Annota 保留全部有中文释义的 770,238 条记录，进行换行标准化及 SQLite 索引转换，不按词频或考试标签筛选。
上游包含多种资料来源；仓库代码许可不等于所有外部材料的单独授权证明。

## CC-CEDICT

CC-CEDICT 社区维护，MDBG 发布；来源版本 2026-10-19。
来源：https://www.mdbg.net/chinese/dictionary?page=cc-cedict
许可：Creative Commons Attribution-ShareAlike 4.0 International（CC BY-SA 4.0）。
包括 Paul Andrew Denisowski 的贡献，原始署名见 licenses/CC-CEDICT-header.txt。
完整许可：licenses/CC-BY-SA-4.0.txt；原始源数据：sources/cedict.txt。
原始释义保持不变；zh-en.sqlite 的格式转换和数据组合按 CC BY-SA 4.0 分发。

## 字体与输入法

界面和指南所用字体许可另附。系统手写使用设备已安装的输入法，其网络行为由输入法管理。

## WordNet / Chinese Open WordNet

Princeton WordNet 3.0，Copyright Princeton University；Chinese Open WordNet，Copyright (c) 2013, 2014 Francis Bond and Shan Wang。
完整许可：licenses/WordNet-3.0.txt、licenses/Chinese-Open-WordNet.txt。
来源：https://wordnet.princeton.edu/ 与 https://github.com/omwn/omw-data/tree/406bf83b3c507a3d1f26e88252d5d66893fd36bf/wns/cow 。
随包 sources/wordnet-cow.json 按同义词集关联中英文，保留英文释义、词义顺序及不规则词形；wordnet.sqlite 提供独立只读索引，展示时去除中文分词标记“+”。
共有 61,340 个英文词头、77,708 条词义关联、原始 6,053 条不规则词形关联（另补充 LemmInflect 显式词形），与主词库存在重叠，不计为同等数量的新增单词。

仓库保留上述来源的 .gz 压缩文件；Android 打包时自动展开，APK 内使用不带 .gz 的文件名。

## English Wiktionary / Kaikki

English Wiktionary contributors，CC BY-SA 4.0。提取自 https://kaikki.org/dictionary/English/ 。
独立 wiktionary.sqlite 包含 48,077 个词头、61,076 个义项，和主词库存在重叠。
仅使用带普通话译词且有义项说明的记录，筛除古旧/罕见标记，建立词形和词头索引。
来源哈希及转换说明：sources/kaikki-provenance.json；详细署名：licenses/Wiktionary-NOTICE.md。
CC BY-SA 4.0 许可文本已随包提供。词条作者与历史可通过 Wiktionary 同名英文条目查阅。

## LemmInflect

Bradley J. Ascob, LemmInflect，MIT。https://github.com/bjascob/LemmInflect
固定提交 b7699808106a4ce843fc7f0e8e5d87fcb84cc636，导入查找表与上游 overrides，仅使用显式非自身词形候选。
上游词形资料源自 NIH SPECIALIST Lexicon；原始资料条款：https://lhncbc.nlm.nih.gov/LSG/Projects/lexicon/current/web/termsAndConditions.html 。
来源：sources/lemminflect-provenance.json；完整 MIT 许可：licenses/LemmInflect-MIT.txt。

## 离线参考翻译

OPUS-MT / University of Helsinki，Xenova ONNX 量化转换，Microsoft ONNX Runtime。
模型、分词器、推理均在设备本地，无运行时下载，遥测关闭。
完整许可和模型来源另见应用关于页面“开源与词库许可”中的离线翻译段落；译文属于机器生成参考，不能视为已校验词典释义。
