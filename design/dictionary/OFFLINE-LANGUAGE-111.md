# 1.1.1：离线词典与短句调研、实现边界

状态：待发布源码；公开稳定版仍为 1.1.0。运行时不联网、不下载词库或模型，不调用 Google 服务。使用开源资源，不把第三方数据宣称为原创词典。

## 选择与取舍

| 项目 | 本轮用途 | 取舍 |
| --- | --- | --- |
| [English Wiktionary / Kaikki](https://kaikki.org/dictionary/English/index.html) | 提取有明确普通话译词的英文词头，按翻译记录自身的义项说明建立索引 | 与先前评估的“中文 Wiktionary 的英文提取”不是同一数据集；过滤罕见、古旧译义。不把 `_dis1` 自动消歧序号当作可靠人工配对。 |
| [LemmInflect](https://github.com/bjascob/LemmInflect) | 导入固定提交的词形查找表和上游 overrides | 只采用非自身词形关联；保留多个候选。其 POS 不是通用词性标注器，不用于判断句法。 |
| WordNet + Chinese Open WordNet | 保留既有词义集合和释义线索，补充形态归一 | 语义覆盖与中文对应质量不一致，不能替代所有词典。 |
| FreeDict eng-zho | 本轮不采用 | 前期抽查存在术语错配，增加来源不等于提高质量。 |
| [OPUS-MT en-zh](https://huggingface.co/Helsinki-NLP/opus-mt-en-zh) / [Xenova ONNX](https://huggingface.co/Xenova/opus-mt-en-zh) | 本地英文→简体中文参考译文，独立于词典卡片 | 量化 encoder + merged decoder 约 113 MB 原始权重；短句可用但存在术语/多义词错误，不能宣称精确语境翻译。 |
| [ONNX Runtime Android](https://onnxruntime.ai/docs/tutorials/mobile/) | Android CPU 推理，2 个 intra-op 线程、单翻译队列 | 关闭遥测；闲置 45 秒后释放 session。保留后台推理及取消检查，避免阻塞绘制线程。 |

## 实际覆盖与可复现数据

English Wiktionary 原始冻结数据包含约 149.9 万条英文词性记录，**只有带普通话译词的子集进入本项目**。筛选后独立数据库为 48,077 个词头、61,076 个义项，其中 15,628 个多词词头。按相同规范化方式与 ECDICT 词头集合比较，11,054 个词头不在旧集合中，其中 6,187 个多词词头。这个差值不是人工验证过的正确翻译数。

LemmInflect 固定提交 `b7699808106a4ce843fc7f0e8e5d87fcb84cc636`，冻结提取 80,760 条含词性记录、70,064 种表面词形，41,024 个非自身 form→lemma 对。每个索引只接受其词库实际存在的原词，避免把没有译义的候选当成命中。

源码、出处、哈希、许可证分别位于 `sources/*-provenance.json`、`licenses/`。`kaikki-provenance.json` 的 extract/overlap 字段描述**进入构建过滤之前的提取集**，不能用来代替最终数据库数量。最终数值以 `bundle/manifest.json` 和数据库查询为准。Wiktionary 是独立 CC BY-SA 4.0 数据库；LemmInflect 是 MIT。APK 包含对应输入和署名。

```sh
# 从仓库冻结的源离线重建所有数据库
python3 design/dictionary/build.py
python3 design/dictionary/test_lookup.py
python3 android/scripts/sync-assets.py
```

`import_kaikki.py` 与 `import_lemminflect.py` 提供原始来源提取器；来源 URL 可能更新，应先核对 provenance 哈希。Kaikki gzip 重新提取的压缩头可能不同，按解压后的记录验证语义一致性；规范输入是仓库冻结文件。

## 检索变化

- 精确词头之后继续关联显式词形，当前 Wiktionary 普通释义补充旧 ECDICT；旧释义仍可展开。
- 未整体收录的多词选区按最大覆盖、最少分段分解；Wiktionary 多词索引也参与匹配。标点阻止跨分句组合，连字符与空格保留等价匹配。
- 分段后重新查全部候选义项，不只传入最先找到的一条。词组可还原任一单词的一层显式词形；不是无限枚举所有屈折组合。
- 局部上下文和词典英文释义均做显式词形归一，用内容词重合排序。至少两个独立内容词、无同分冲突才提升义项；同一词的原词和屈折形不能重复充当两条证据。
- 技术缩写保留大小写规则，已有明确搭配仍有效。证据不足时保持默认释义及可展开的其他释义，不能保证每次选中上下文最适合的词义。

## 短句不是词典拼接

模型固定于 Xenova 提交 `046f55aec303cdee3e0318604406d4df20f1e8ea`。资产 manifest 记录每个模型/分词文件 SHA-256；编码/解码模型在首次使用前从 APK 校验复制。Kotlin Unigram 分词器与独立 SentencePiece 生成的 16 组向量比较，覆盖 Unicode、空白、符号与未知字符。构建导出器在 `design/translation/export_tokenizer.py`；构建主机需要 SentencePiece，应用不需要 Python。

两词以上、未整体命中词条的英文输入自动显示“离线参考译文”。词典先返回，推理在单独后台线程运行；改词/关闭面板使旧请求失效，每次解码检查取消。CPU 推理使用贪心解码；研究中 beam=4 没有稳定改善，未增加其耗时。最大 512 字符 / 64 个英文词、128 个源 token、160 个输出 token、20 秒推理期限。过长输入不截断为另一句。汉英仍为词典查询。

上游模型卡标注 Apache-2.0，OPUS-MT 项目一般说明另提 CC BY 4.0；本项目保留双方原文、完整 Apache 许可和署名。ONNX Runtime MIT 许可也随包。正式候选仅打包 ARM64 库，符合当前支持的 Xiaomi Pad 9 Pro Max。

### 已知质量限制

原始模型的独立探针发现：`The server scales to meet demand.` 可误译为“满足需求所需的服务器比例尺。”；`distributed grid` 可能误解为电网，`controlled for age and income` 也可能丢失统计控制语义。没有为这些句子硬编码替换。更多词库与形态索引改善查词覆盖，但并不自动修正翻译模型。

因此输出明确是参考译文，词典分段结果仍在下方。用户应能直接点开单词核对；不以“测试通过”代表通用翻译准确率。下一步语义质量提升需要固定的跨领域人工评测集和更好的可离线模型，不能靠继续堆积样例规则解决。

## 验证

Android 16 / API 36 ARM64 专用模拟器，Wi-Fi/移动数据关闭，测试原有检索、词形、独立短句模型、快速换词与关闭取消，以及本轮阅读菜单/顶部栏修复。最终证据见 [1.1.1 验证目录](../../android/verification/stable-1.1.1/)。模拟器测试不是目标平板实测；翻译延迟、发热和内存仍需真机复核。
