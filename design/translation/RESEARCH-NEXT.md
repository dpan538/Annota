# 离线长词组与短段落：增量研究

研究日期：2026-11-03。服务于 1.1.1 待发布版本；本文不是发布公告。优先补强词库及可解释检索，不以模型生成结果替代词典收录义项。没有把私人课件送到网络服务；以下探针为单独编写的合成英文。

## 本轮选择

维持已内置的 OPUS-MT en→zh 量化模型，扩展输入规划与请求生命周期。能在单次 128 源 token 内容纳的内容保持整体，避免无理由丢失跨句线索；超过时仅按完整句边界分段。每段都先检查预算，任何一段过长就明确拒绝，不静默截断、不按逗号截开条件或否定。为整体请求设置时间、段数和取消限制。缓存仅保留本次进程内的少量结果，不上传阅读内容。

**不把未收录的单个长词交给模型自动补义。** 本地探针中 `electroencephalography` 被译为“电脑摄影”、`immunohistochemistry` 被译为“免疫史化学”。这种输出虽然含中文、看起来流畅，仍然不可靠。单词应先通过显式词形、专业词库及可溯源英文释义补充；多词输出继续独立标注“离线参考译文”。

## 已有开源项目怎样处理

| 来源 | 可借鉴能力 | 当前判断 |
| --- | --- | --- |
| [Argos Translate](https://github.com/argosopentech/argos-translate) / [句界检测实现](https://github.com/argosopentech/argos-translate/blob/master/argostranslate/sbd.py) | MIT 离线翻译应用栈；使用句界检测，再调用模型；可打包句界模型 | 借鉴分句和独立模型包的职责划分。Python、分句器与 CTranslate2 整套迁入 Android 成本高；库许可证也不能代替每个模型包的许可核验。默认模型下载路径不适合本产品，不引入。 |
| [OpenNMT 约束解码](https://opennmt.net/OpenNMT/translation/beam_search/) | 在译码时利用术语表约束输出，而不是译完后替换字符串 | 需要可靠术语义项和对应的译码实现。不能把任意查询的第一个中文义项强制塞进译文。 |
| [HOLLY / PLUMCOT 研究](https://github.com/papago-lab/HOLLY-benchmark) | 同形词的词汇约束须判断语境是否适用；提供正负约束样例 | 借鉴“错误约束也要测试”的评测方式。该数据针对韩英，不当作英汉词库导入。 |
| [SentencePiece](https://github.com/google/sentencepiece) | 子词分词覆盖未见词形，维持冻结分词器与模型一致 | 仅是分词方法，不保证生僻词译义正确；现有源分词器向量回归继续保留。无需引入在线服务。 |

## 候选模型核验

这些是下一阶段评估候选，不是已采用或已证明优于当前模型。

- [OPUS-MT en-zh 原模型](https://huggingface.co/Helsinki-NLP/opus-mt-en-zh)：现有模型卡标注 Apache-2.0，要求目标语言前缀；当前已固定量化版本、哈希、简体前缀。无需扩大包体即可修复输入生命周期和不完整输出问题，但不能因此承诺专业语义改善。
- [M2M100 418M](https://huggingface.co/facebook/m2m100_418M)：官方 MIT，多语言 encoder-decoder。若进入下一阶段，需用同一英汉评估集、量化构建和目标平板比较，不能只凭参数量替换。418M 参数的 int8 原始权重数量级约 418 MB（算术估计，不是 APK/实际内存测量），远高于现有约 113 MB 编码器+解码器。
- [NLLB distilled 600M](https://huggingface.co/facebook/nllb-200-distilled-600M)：官方 CC BY-NC 4.0。非商业条件会改变分发边界，本轮不采用；公开可下载不等同于无限制开源。
- [HY-MT1.5 1.8B](https://huggingface.co/tencent/HY-MT1.5-1.8B)：官方列有语境与术语干预能力，但 [许可](https://huggingface.co/tencent/HY-MT1.5-1.8B/blob/main/License.txt) 是 Tencent HY Community License，包含地域限制及分发条件，并非 Apache/MIT。官方原始权重约 4.08 GB，量化也需专门端侧评估。本轮不纳入 APK，不沿用其输出训练现有模型。

## 可复现的独立诊断

- [`evaluation/cross-domain.json`](evaluation/cross-domain.json)：32 条合成探针，覆盖日常、计算机、金融、法律、科学、长词组、多句、单个长词。中文 `review` 是评审需核对的语义，不是预制译文，也不进入 APK 或生产代码。
- [`evaluation/opus-int8-baseline.json`](evaluation/opus-int8-baseline.json)：当前冻结模型实际 CPU 输出、token 数、EOS 完成情况、时间及模型哈希；既保存成功也保存错误译文。
- [`evaluate_offline.py`](evaluate_offline.py)：只读取本地模型与 SentencePiece，不下载或发请求。测试机器是 macOS，不是 Android，也不代表真机延迟。

```sh
python design/translation/evaluate_offline.py \
  --assets android/app/src/main/assets/translation \
  --source-spm /path/to/frozen/source.spm \
  --output /tmp/opus-int8-baseline.json
```

构建机需 `numpy`、`onnxruntime`、`sentencepiece`；它们不作为 Python 运行时进入 Android。当前冻结源 SentencePiece 的 SHA256 记录在结果文件，可与上轮缓存核对。

### 观察而非准确率结论

日常的火车晚点、收据退货、调低音量，以及复利和债券到期样例保留了主要语义。仍有可见失败：`cache miss` 被译成“缓存错误”；`forward secrecy` 成了“远端保密”；膜的渗透关系倒置；法律 `without prejudice` 未表达其实际法律含义。不能通过仅检查“有中文”把这些判成正确。

四个短多句的整体/分段对照没有一致的质量提升：商店的 `orders` 两种方式都译成“命令”。长段落分句结果中，订房情境的 `ticket` 仍被译为“罚单”。分段价值是预算和边界完整性，不能宣传为准确率改进。因此只在整段超 token 预算时分段。

另外两条较长四句输入分别为 131 和 139 源 token，旧单段预算均明确拒绝；各自四段均产生 EOS，CPU 总推理时间分别约 1.70 秒和 1.74 秒（本机测量，不代表平板性能）。它们用于验证各句分别可处理，以及缩写、小数和句尾的边界。新分段逻辑必须接受整段或明确失败，不能拿前面成功的几句冒充完整翻译。

这 32 条是开发诊断集，不是随机抽样，也不是独立人工标注的机器翻译基准。下一阶段需固定更大跨域留出集，由双语评审核对术语、否定、数量、条件和遗漏；可同时报告 chrF 等参考指标，但不把一个指标或这组探针变成泛化准确率。
