# Annota 离线词典包

英汉 / 汉英词库随 APK 提供，查词只读本机 SQLite。不会上传查询词、手写轨迹或 PDF，也不会在查无词条时自动请求在线词典。

## 数据与覆盖

| 方向 | 数据 | 条目数 |
| --- | --- | ---: |
| 英 → 中 | ECDICT 全部中文释义记录 + Annota 原创词组与术语 | 771,180 |
| 中 → 英 | CC-CEDICT + 原创短释义的反向索引 | 126,272 |

新增 **942 条**独立编写的简短词组 / 术语，另有 **682 个**显式记录的词形和拼写别名。别名不计为新增词条；未通过组合模板制造数据量。九个主题覆盖：

- 常用短语动词 135 条，学术连接表达 92 条，论文阅读与研究方法 78 条。
- Attention / 自然语言处理 77 条，机器学习 134 条，视觉与模型 76 条。
- 数学与统计 133 条，软件与系统 102 条，科学与工程 115 条。

例如 `set up`、`take into account`、`in terms of`、`recurrent neural network`、`scaled dot-product attention`、`confidence interval`、`palm rejection`。释义以完整的短等价表达为主，不堆长解释。词库并非穷尽所有领域；条目数也不是去重后的单词数。

1.1.0 另内置 WordNet / Chinese Open WordNet 独立语义索引，覆盖 61,340 个英文词头、77,708 条英中词义关联。与主词库存在重叠；来源、筛选与算法边界见 [开放词库调研](OPEN-LEXICON-RESEARCH.md)。

## 匹配规则

1. 优先精确词条 / 显式别名，再按保留词边界的连字符与空格等价匹配。
2. `set up` 与 `setup` 分开，`neuralnetwork` 不冒充 `neural network`；猜测仅出现在可点选建议中。
3. 支持已记录的词形，例如 `neural networks`、`carried out`、`taken into account`。不随意截词尾猜词根。
4. 全半角、大小写、Unicode 连字符、软连字符统一；`self-\nattention` 保留词边界，`neural net-\nwork` 在未命中时尝试修复断行词。
5. 阅读中选中单词时，可以结合本地同栏、同句的相邻词匹配最长短语；同长度结果冲突时不强行选义。整个词组默认只显示一条简洁释义。
6. 中文同时查简繁体。新增原创反向等价表达写入同一个汉英库；已有 CC-CEDICT 数据保持原样。

词典接收最多 160 字符，超过时返回空结果，不静默截成另一个查询。未整体收录的英文选区（2–40 个词）按最大覆盖、最少分段查询，保留标点边界，并用来源明确记录的词形还原词组中的单词。结果明确标为“分段释义”，未知片段保留并标注未收录，不冒充整句翻译。

1.0.7 新增保留大小写的技术缩写层（9 个缩写、12 个释义），并把分段查词的邻词传入点开的词条。技术词义与日常词义按本地线索排序；来源、编辑规则和边界见 [技术术语说明](TECHNICAL-TERMS.md)。

## 可复现构建与检查

```sh
python3 design/dictionary/build.py
python3 design/dictionary/test_lookup.py
python3 design/dictionary/lookup.py 'multi-head attention'
python3 android/scripts/sync-assets.py
```

以上过程无需网络。`sources/annota-phrases.tsv` 是原创词组源文件，分组注释与逐条释义均可审阅；第三列为 `|` 分隔的显式别名。构建检查重复词条、长度和短释义，测试逐一查询所有新增词条与别名，并检查词边界、断行和反向查询。

`sources/` 包含重建所需数据；`bundle/manifest.json` 记录 SQLite schema 3、原始 / 扩展数量、分类、许可和 SHA-256。数据库包含匹配与拼写建议索引，准确大小和哈希以 manifest 为准。首次准备 ECDICT 数据可用 `build.py --ecdict /path/to/ecdict.csv`；没有参数时使用已保存的本机源文件。

大型数据库以 gzip 随 APK 提供，安装时按 manifest 解压、原子写入并校验；无需运行时下载。查询在后台线程使用参数绑定、只读连接；旧版词库在更新后按哈希更换，不修改用户文档。全部词组随应用更新提供，无额外下载。

## 来源与许可

- **Annota 原创词组** — 2026 Dai Pan and Annota contributors，MIT。短释义与别名为项目原创编辑，没有复制外部词典解释。完整逐条源文件与许可随 APK 提供；来源、范围和转换说明见 `sources/provenance.json`。中文反向索引只使用完整等价表达，含“……”的句式不反向生成。
- **ECDICT** — Linwei / skywind3000，MIT。来源：[项目](https://github.com/skywind3000/ECDICT)，许可在 `licenses/ECDICT-MIT.txt`。保留全部有中文释义的 770,238 条记录，不再按词频或考试标签筛选；原始数据见 `sources/ecdict-core.csv.gz`。上游说明数据来自多种资料，其来源链说明保留于应用署名。
- **CC-CEDICT** — 社区维护、MDBG 发布，2026-10-19 版本，CC BY-SA 4.0。[下载与许可](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)。125,301 条原始记录保留；头部署名见 `licenses/CC-CEDICT-header.txt`，包括 Paul Andrew Denisowski 的贡献。`zh-en.sqlite` 作为转换和组合数据按 CC BY-SA 4.0 分发；原创 TSV 可独立按 MIT 使用。源数据为 `sources/cedict.txt.gz`。

应用“关于 → 开源与词库许可”离线展示署名和许可。词典没有在线发音，也没有自动遥测、登录或云同步；系统输入法有独立网络行为，需在目标设备飞行模式下单独验收。

## 1.1.1 待发布：扩展词源与短句

- **English Wiktionary / Kaikki**：独立的 CC BY-SA 4.0 索引，48,077 个词头、61,076 个义项，其中 15,628 个多词词头。与 ECDICT 规范化词头相比新增 11,054 个，其中 6,187 个为多词词头；不同来源总数不能直接相加。
- **LemmInflect**：冻结 MIT 词形表的 41,024 个非自身词形→原词关联。仅导入表内候选，不安装 Python 包或神经词形模型，不用后缀猜测制造词条。
- 上下文排序同时对英文释义和邻词做显式词形归一，至少两个独立内容词命中且没有并列冲突时才提升词义。分段保留备选义项；不能保证理解所有上下文。
- **离线参考译文**：未命中完整词条的英文长词组和短句可用内置 OPUS-MT 量化模型翻译为简体中文。模型、分词数据和 CPU 推理库全部随 APK；首次打开也不需要联网。支持 2–64 个英文词、最多 512 字符，编码上限 128 token。汉英仍为词典查询，不支持中文整句翻译。
- 译文与词典释义分开呈现；选词优先查询词典。复杂句、专名和术语仍可能错译，不能把分段释义或模型输出视为已校验的译文。

实现、来源比较、许可证和已知失败样例见 [1.1.1 调研与实现](OFFLINE-LANGUAGE-111.md)。当前公开稳定版本仍为 1.1.0；本节描述未发布代码。
