# 1.2.0 检索升级：新词库与离线检索技术

2026-11-08（Australia/Brisbane）。基线：1.1.1（versionCode 21）。本文件记录实现范围、数据来源、
算法边界与可复跑的验证方式。所有数据、模型与索引随 APK 提供，运行时无网络。

## 1. 解决的问题

1.1.1 的审查发现两类确定性问题：

- **伪义项**：Kaikki 的普通话译词有时挂在 `Translations` 这类章节标题下，而不是具体义项释义下；
  旧构建把它当 gloss 写入 `senses`，共 1,786 条。排序 `ORDER BY rank,pos` 让 `cut` 的
  `intj / rank 0 / 义项“卡”` 排到最前；无上下文时 `DictionaryReading` 会把它选为第一释义。
- **检索覆盖与延迟**：语义词库只来自 COW 的 42,300 synset；长选区逐段查询、每次重新打开
  SQLite；拼写建议在大候选集上做 O(n·m) 编辑距离；中文只做精确简繁匹配。

## 2. 数据与词库

| 数据 | 变化 |
| --- | --- |
| en-zh | 771,180 条不变；新增 ECDICT `bnc`/`frq` 词频秩列（56,992 词有值，只用于排序） |
| zh-en | 126,272 → 129,801 条；+971 原创反向条目、+3,529 OMW 中文词网新增词；新增 `variants` 繁简字表 4,148 条 |
| wordnet | 77,708 → 95,221 条词义关联；61,340 → 72,948 词头；42,300 → 50,639 synset；新增 `sense_order`/`sources` 列 |
| wiktionary | 61,076 → 59,290 义项（移除 1,786 条伪义项）；新增 `sense_order`；其余词形/英文兜底表保留 |
| 语义索引 | gloss 向量 92,869 → 101,085；按 wordnet/wiktionary 新 gloss 全量重建 |
| Bloom 位图 | 1,316,131 个可检索 key，10 bit/key，7 次哈希，约 1.65 MB，理论假阳性 ≈0.8% |

### 新开源词库

- **Chinese WordNet（CWN，Academia Sinica）**：4,913 synset / 8,069 中文 lemma。经 OMW
  `wns/cwn/wn-data-qcn.tab` 引入，许可与署名见 `licenses/cwn-Academia-Sinica-license.txt`。
- **OMW Wiktionary Mandarin**：12,130 synset / 19,079 中文 lemma。经 OMW `wns/wikt/wn-wikt-cmn.tab`
  引入，CC BY-SA 3.0，见 `licenses/omw-wikt-CC-BY-SA-3.0-NOTICE.md`。
- **Open English WordNet 2025**：只用可经 CILI 对齐到 PWN30 的 synset 英文释义与同义词；
  25,929 个 CWN/Wiktionary 新增 synset 通过 ILI 获得英文释义；CILI 映射见
  `licenses/cili-CC-BY-4.0.txt`。未对齐的 3,184 个 `ili="in"` synset 不进入索引。
- **Princeton WordNet 3.0**：提供 synset 词形、英文释义与义项频率序（`index.sense` 的
  sense_number，不是物理行序）；许可 `licenses/wordnet-3.0-license.txt`。
- **OpenCC `TSCharacters.txt`**：4,148 个繁→简单字映射，构建为 `zh-en.sqlite.variants`；
  未链接 OpenCC 运行时，署名见 `licenses/opencc-NOTICE.md`。
- **ECDICT 全量词频列**：启用上游 `bnc`/`frq`（MIT），`build.py --ecdict` 重新保存核心源时保留。

上述仅构建期使用的源文件放在 `design/dictionary/sources-build/`（7 个 gzip，共约 18.0 MB），
**不进入 APK**；`android/scripts/sync-assets.py` 只同步 `sources/`。manifest 记录其 SHA-256、
上游 URL、版本与许可。

## 3. 检索技术

1. **Bloom 过滤器预筛**：构建期把所有可检索 key（en-zh 的 entries.key、aliases.key、
   aliases.boundary_key、forms.key，以及 wiktionary 的 aliases.key、forms.form、
   source_forms.form、english_aliases.key）写入 `en-zh.sqlite.lookup_filter`。
   Kotlin 侧用相同约定（SHA-256 前 4/8 字节作 h1/h2，大端 32 位，LSB-first 位图，
   `index=(h1+i*h2) mod nbits`）判断“可能存在”；只允许假阳性，不允许假阴性。
2. **持久只读连接**：`Dictionary` 与 `WordNetDictionary` 复用 en-zh / zh-en / wordnet /
   wiktionary 的只读连接，`close()` 后可重开；不再每次查询 open/close。
3. **查询与负结果缓存**：按规范化文本 + 语境签名缓存 `Result`（含空结果），
   取消中的查询不会写入缓存。
4. **Myers 位并行编辑距离**：≤64 字符的字符串用位并行 Levenshtein（保留相邻换位语义），
   长度差 >2 直接返回上界；与旧 DP 全量对拍。
5. **词频排序**：建议候选按（编辑距离、ECDICT 词频秩、词形）排序，词频 0 视为最低。
6. **繁简查询扩展**：中文查询先精确匹配；未命中时按 `variants` 逐字转简体再查并去重。
7. **长选区分段**：Bloom 预筛 + 持久连接 + 局部缓存，减少不存在片段触发的 SQL；
   仪器测试记录 100+ 词选区的实际耗时代替宽松阈值。
8. **义项顺序**：WordNet 与 Wiktionary 查询使用构建期固定的 `sense_order`，
   不再按 `rank,pos` 二次排序；伪义项过滤与稳定顺序同时生效。

## 4. 边界与已知限制

- 词库合并后的条目数不能与主词库相加；CWN/OMW 中文释义未经逐条人工校对。
- OEWN 只贡献英文释义/同义词，不新增中文对应；CILI 对齐失败时保留 COW 原释义。
- Bloom 过滤器只用于“可能不存在”的短路；语义重排、翻译与英文兜底仍走原逻辑。
- 建议排序使用词频先验；低频专业词的拼写建议可能被常用词排在前面，仍可展开其他候选。
- 繁简扩展是逐字映射，不做词组级异体转换；多音/一繁对多简的字形只取 OpenCC 首个候选。
- 仪器化测试在专用模拟器执行；手写延迟与目标平板手感仍须真机验收。

## 5. 可复现构建与验证

```sh
python3 design/dictionary/build.py            # 约 45 秒，离线
python3 design/dictionary/test_lookup.py      # 16 项
python3 design/dictionary/test_lexicons.py    # 20 项
python3 design/dictionary/semantic/build.py   # 需 onnxruntime、tokenizers；批量=1，约 3.5 分钟
python3 design/dictionary/semantic/finalize.py
python3 android/scripts/sync-assets.py
```

构建可重复：同一批源文件两次运行得到相同的 4 个 SQLite SHA-256；`sources-build/` 文件的 gzip
`mtime=0`，二次生成字节一致。原始回归、构建哈希、APK 校验与仪器测试输出保存在
`android/verification/` 对应版本目录。
