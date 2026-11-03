# 离线词库扩充与检索覆盖研究

2026-11-03；属于 **1.1.1 待发布源码**。本轮没有发布或改变更新通道。

## 本轮选择

优先利用已有公开词典中尚未索引的显式词形和实质释义。扩充面向普通阅读、习语、科技和其他专业词汇，不围绕某份课件新增硬编码译文。所有提取发生在构建主机；用户端只读取 APK 内置数据库，不下载词典、不调用在线服务。

1. **明确来源的词形索引**：新增 Kaikki/Wiktionary `form_of` 关系，帮助不规则词形和长复合词回到已有译义。它增加的是可检索拼写，不是新的中文释义。
2. **有出处的英文释义兜底**：对现有英汉库没有词头的词，纳入 Wiktionary 的完整英文解释。应用必须明确标注“英文释义”，且英汉检索优先。没有汉译时提供来源明确的解释，比将单词交给翻译模型后无标识地当作字典义项更可核验。

[Kaikki](https://kaikki.org/dictionary/English/) 提供由 Wiktextract 提取的词性、词形、义项与领域字段；在线数据会变化。本项目所有数字来自 provenance 固定的原始 gzip（534,186,395 bytes，SHA-256 `4d03b6cba283744756c57ff661c2c6cbfe34fcf8ff513a966576cf7d97e91a99`），不是网页最新总数。[Wiktionary 数据许可](https://en.wiktionary.org/wiki/Wiktionary:Copyrights)与应用代码分离，选择 CC BY-SA 4.0 路径，保留来源、词头、修改说明和重建输入。

## 词形数据：边界比数量更重要

冻结源 `sources/kaikki-forms.jsonl.gz`：473,958 个 `(form, lemma, pos)` 记录，462,400 种表面词形，129,943 种多词表面词形，gzip 3,062,036 bytes。

仅接受普通 noun/verb/adj/adv、明确 `form-of` 与屈折标签、单个义项只有一个 `form_of` 目标、释义末尾完整指向目标的关系。拒绝古旧、误拼、非标准和方言标签。**不导入 `alt_of`**：原始抽样中该字段可能混入释义链接的地名/地区，而不是同义词头。不同义项中合法的多个目标仍保留，例如 `axes → axis / axe`，不能按扫描顺序擅自选一个。

与本次扩充前 ECDICT、Wiktionary 汉译、WordNet-COW 的词头及已有词形比较，211,091 个关系的目标已有释义；其中增加 121,155 种可检索词形，32,554 种为多词，535 种表面词形有多个合法目标。这是**源提取审计口径**，集成后增加英文释义目标或更新其他索引会改变最终 SQL 数字，不能混用。

固定随机种子 111 抽查 80 条新增关系，覆盖 `autocollimators → autocollimator`、`perovskites → perovskite`、`cytoarchitectures → cytoarchitecture`、`recrystallizes → recrystallize`，以及 `wimped out → wimp out`、`did one's duty → do one's duty`、`jumping for joy → jump for joy`。它们在源中有明确屈折链接。抽样也存在罕见词，不据此宣称所有新增记录均为高频、已人工确认的正确解释。

## 英文释义：保留解释，拒绝空引用

冻结源 `sources/kaikki-en-definitions.jsonl.gz` 包含 **192,058 个词头、208,999 条完整英文释义、53,742 个多词词头**，gzip 7,484,730 bytes。对应 provenance 记录源文件及基线数据库哈希；这不是新增中文翻译的数量。提取器 `import_kaikki_english.py`：

- 普通四类词性、小写词头，排除当前三个英汉来源已有的精确词头。
- 去除词形/替代形式、纯 `Synonym of` / `Alternative spelling` 等交叉引用、简单派生释义、古旧/罕见/非标准标签、明显人名地名及缺少先行说明的释义。
- 没有额外使用标签 UI，因此暂不纳入带 offensive / derogatory / vulgar 标签的义项，避免丢掉原词典的使用警告。
- 每个词头按源顺序最多三条不同实质释义；20–500 字符、至少五个词，整条保留，超长则排除，不截成不完整解释。
- 领域 topics 保留在冻结源，当前查词不借此强行推断用户一定在某个领域。

实际新增例子：`hosts file` 解释操作系统将主机名映射到 IP 地址的文本文件；`hematitization` 解释矿物转化成赤铁矿；`clump up` 解释结块。这些条目在原英汉词头集合中缺失，不是同一课件的定制翻译。

质量限制：社区词典会有低频造词、简略释义、拼写错误或个别专业解释不准确。例如随机样本出现带拼写错误的化学说明和不够独立的派生词解释。自动筛选不能保证词义正确，也不能把新增英文词头总数宣传成新增汉译。低风险的下一步是跨领域人工评估集、来源标签与公开数据修订，而不是批量生成中文译文填空。

## 其他公开数据的实际收益

| 来源 | 许可与判断 | 本轮处理 |
| --- | --- | --- |
| [CNCF Glossary](https://github.com/cncf/glossary) | 文档 CC BY 4.0；代码 Apache 2.0。固定提交 `c6493fa4824c440cca36a970f9ca68eeabc544bb`，对英文/简中同名页面提取标题，再要求中文含汉字，得到 65 对、44 个现有词头未覆盖项。 | 适合未来云原生领域补充；规模过窄，未作为本轮通用词库主来源。`scaling` 等多义词不能全局覆盖。此数字不含别名展开。 |
| [Rust 中英术语表](https://github.com/rust-lang-cn/english-chinese-glossary-of-rust) | CC0；Rust/编程语义相关，原项目也提示可能存在错误。 | 可与其他明确许可的领域表一起做带领域的增补，不替代普通词义。 |
| [CJK computer science terms](https://github.com/dahlia/cjk-compsci-terms) | CC BY-SA 4.0；包含不同地区术语。 | 可补术语与别称，但必须按大陆简中列取值，不能把台港词汇机械转简体等同大陆用法。 |
| [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict) | CC BY-SA 4.0；主要是中→英词典。 | 已有源可做反向候选研究，但本轮不批量反转为确定英汉译义。 |
| [CSET-ETO technical glossary](https://www.eto.tech/tool-docs/glossary/index.html) | 公开表格，涵盖科技、安全等术语，但本轮没有确认允许再分发整个数据集的明确许可。 | 不因“可公开访问”就打包进 APK。先明确再分发许可。 |

CC-CEDICT 实验只取完整英文短义项，限制至五词并排除显然元说明，仍产生 20,114 个旧英汉集合没有的候选（19,752 个多词）。其中有 `chemical base → 碱基` 这样的范围错配，还有说明性短语，并非等价翻译。数量可观并不足以证明能提高检索准确性，因此没有合并。Microsoft 术语下载同样不能因网页文档开源，就推断 TBX 数据使用条款允许随 APK 再分发。

## 重建与验证

```sh
# 原始 gzip 保存在构建机缓存；先核对 provenance 的 SHA-256。
python3 design/dictionary/import_kaikki_forms.py \
  android/.tooling/research-111/kaikki-English.jsonl.gz design/dictionary/sources
python3 design/dictionary/import_kaikki_english.py \
  android/.tooling/research-111/kaikki-English.jsonl.gz \
  design/dictionary/bundle design/dictionary/sources
python3 design/dictionary/test_kaikki_forms.py
python3 design/dictionary/test_kaikki_english.py
```

英文源以提取时三个汉译库为排除基线，哈希记录在 provenance；后续数据库变化会使重新提取的内容变化。因此发布构建使用仓库已冻结输入，运行时仍必须再次执行中文结果优先规则。两个提取器都无网络行为。最终 APK 大小还包含索引、完整版权输入和既有模型，不能只用 gzip 大小代表安装包增量。

测试验证过滤边界、歧义保留、数据哈希、义项数量上限、完整释义和真实新增词。它们验证程序与数据约束，不代表整个词库人工质量验收，也不替代 Android 真机体验测试。
