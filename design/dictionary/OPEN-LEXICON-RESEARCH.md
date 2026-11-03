# 本地词典检索与开放词库

以下保留 1.1.0 的来源评估；1.1.1 对不同的 English Wiktionary 数据集做了新的筛选并纳入，见 [1.1.1 调研](OFFLINE-LANGUAGE-111.md)。

## 采用的材料

- [ECDICT](https://github.com/skywind3000/ECDICT)：继续保留全量中文释义记录；使用来源显式提供的 `exchange` 词形及 `0:` 原词关联。精确命中后仍补充原词，避免 `scaling` 等派生词挡住原词义项。
- [Princeton WordNet 3.0](https://wordnet.princeton.edu/) 与 [Chinese Open WordNet](https://github.com/omwn/omw-data/tree/406bf83b3c507a3d1f26e88252d5d66893fd36bf/wns/cow)：按同义词集 ID 连接，不把两本词典的字符串直接拼接成译文。独立 SQLite 包含 61,340 个英文词头、77,708 条英中词义关联、6,053 条不规则词形关联；这些数目与 ECDICT 重叠，不能相加宣称新增单词总数。
- [Wn](https://github.com/goodmami/wn) 的本地词汇数据库与显式词形检索提供架构参考；Android 实现为 Kotlin + SQLite，没有嵌入 Python 运行时或联网依赖。

WordNet 与 COW 的完整许可随源文件及 APK 提供，并可在应用的开源许可中阅读。COW 固定版本为 `406bf83b3c507a3d1f26e88252d5d66893fd36bf`。来源数据独立保留，应用查询只读本地索引，更新只随 APK。

## 排序与边界

1. 精确词 / 别名优先，继续关联来源明确记录的原词；不盲目删除词尾。
2. 原释义拆成短义项供展开，避免只保留第一组同义词。WordNet 中文对应词作为独立补充；默认仅在两份来源相互印证时调整源释义顺序。
3. 局部领域线索至少两个且没有同分冲突时，可提升源词典已有的领域义项。另用有限窗口与英文释义的内容词重合排序（简化 Lesk），证据不足保留默认顺序。已有明确搭配规则继续有效。
4. 多词选区可分段查词，结果不冒充整句翻译。缩写保留大小写证据，未知词不自动联网。

这不是完整语义理解：同义词集本身可能有宽泛或不准确的中文对应，旧版 WordNet 也不覆盖全部新技术词。不能把词库条数或测试通过解释成翻译准确率。

## 未直接纳入

抽查 [FreeDict eng-zho 2025.11.23](https://download.freedict.org/dictionaries/eng-zho/2025.11.23/) 出现 IT 对应“信息论”、scale 的部分义项错配；不用于覆盖当前简释。抽查 [Kaikki 中文维基词典英文提取](https://kaikki.org/zhwiktionary/%E8%8B%B1%E8%AF%AD/index.html) 后，当前覆盖与义项质量尚不足以直接替换现有库。词频工具能辅助候选排序，但不能独立判断词义。

## 重建

官方输入为 `https://wordnetcode.princeton.edu/3.0/WordNet-3.0.tar.gz` 与固定 COW 提交下的 `wn-data-cmn.tab`。

- WordNet 压缩包 SHA-256：`640db279c949a88f61f851dd54ebbb22d003f8b90b85267042ef85a3781d3a52`
- COW tab SHA-256：`379fd2e41d3e1395f9f27cf23a39c6181849ffb4020c14a07ed2a4d4dd651122`

```sh
python3 design/dictionary/import_wordnet.py WordNet-3.0.tar.gz wn-data-cmn.tab /tmp/wordnet-cow.json.gz
python3 design/dictionary/build.py
python3 design/dictionary/test_lookup.py
```

导入器生成等价的词义集合（同义词排列可不同）；仓库冻结的规范源位于 `sources/wordnet-cow.json.gz`，`build.py` 从此源离线构建，manifest 校验每个数据库与来源文件。
