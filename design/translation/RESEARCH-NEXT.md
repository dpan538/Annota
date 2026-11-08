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

## 1.2.1：整句解码变体与输出后处理（2026-11-08）

本轮问题：在冻结的 OPUS-MT int8 模型与现有分词器不变的前提下，改变解码策略能否**稳定**改善英→中整句输出。评估只在本机 CPU（onnxruntime 1.31.1，2 intra-op 线程）运行，不下载、不联网；macOS 耗时不是平板耗时。

### 固定开发集

- [`evaluation/sentence-quality.json`](evaluation/sentence-quality.json)：40 条自写英文句子 + 人工简体中文参考，覆盖日常、计算机、学术、法律、否定、数字、长句（含接近 128 源 token 的单句）与多句。
- **这是开发集，不是准确率基准**：句子是在实现本轮改动时编写的，不是随机抽样或留出集；每句只有一条人工参考措辞，chrF 只表示与该措辞的接近程度，不表示语义正确率。任何结果都不得写成泛化准确率。
- 参考译文与探针不进入 APK，不用于硬编码替换；解码仍只由冻结模型产生。

### 方法

[`evaluate_variants.py`](evaluate_variants.py) 对同一批 40 句运行 8 个解码策略，输出每句译文、chrF、重复 2/4-gram 比例、最长连续重复块、EOS 完成、超时、每句耗时与失败样例，汇总在 [`evaluation/variant-report.json`](evaluation/variant-report.json)。参数与 shipped 实现对齐：源前缀 `>>cmn_Hans<<`（id 5）、EOS=0、pad=65000 不参与生成、最多 128 源 token / 160 输出 token、每句 20 秒评估期限。变体：

1. greedy（当前实现：argmax、遇 EOS 停止）；
2. no-repeat 4-gram（fairseq 式，禁止重复 4-gram）；
3. repetition penalty 1.2（对已生成 token 的 logit 除法/乘法惩罚）；
4. early-EOS guard（输出不足 4 token 前屏蔽 EOS）；
5. 上述三种廉价策略的组合；
6. beam=2 与 7. beam=4，长度归一化 `score / len^0.6`；
8. beam=4 + 三种廉价策略。

### 结果（开发集，40 句；本机 CPU）

| 变体 | chrF（对参考） | 重复 4-gram 比例 | EOS 完成 | 截断/超时 | 平均耗时 | 相对 greedy |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| greedy | 39.11 | 0.000 | 100% | 0 / 0 | 0.151 s | 1.00× |
| no-repeat 4-gram | 39.11 | 0.000 | 100% | 0 / 0 | 0.129 s | 0.85× |
| repetition penalty 1.2 | 39.34 | 0.000 | 100% | 0 / 0 | 0.136 s | 0.90× |
| early-EOS guard | 39.11 | 0.000 | 100% | 0 / 0 | 0.136 s | 0.90× |
| 廉价组合 | 39.34 | 0.000 | 100% | 0 / 0 | 0.134 s | 0.88× |
| beam=2 + len 0.6 | 39.28 | 0.000 | 100% | 0 / 0 | 0.320 s | 2.11× |
| beam=4 + len 0.6 | 40.70 | 0.000 | 100% | 0 / 0 | 0.550 s | 3.63× |
| beam=4 + 廉价策略 | 40.96 | 0.000 | 100% | 0 / 0 | 0.797 s | 5.26× |

耗时受同一机器上的后台负载影响明显：计算量接近的 beam=4 与 beam=4+廉价策略两次测得分别约 3.6× 与 5.3× greedy，不能把单次倍数当作精确开销；逐句耗时和原始输出以报告为准。

### 稳定性判断：本轮不采用解码变体

- 在 40 条探针上 greedy 已经 100% 生成 EOS、最长连续重复块为 0；no-repeat 4-gram 与 early-EOS guard 对 40 句**全部无变化**（0 胜 0 负 40 平），没有可测收益。
- repetition penalty 1.2 与廉价组合 chrF 均为 +0.23，但只有 3 胜 6 负 31 平，配对 bootstrap 95% 区间约 [−1.56, +2.60]，不能称为稳定改善。
- beam=4 平均 +1.59 chrF、beam=4+廉价策略 +1.85，但配对区间分别约 [−1.70, +5.18]、[−2.22, +6.19]，且为 12 胜 12 负 / 12 胜 13 负：改善集中在法律/数字/日常的个别句子，长句与多句反而变差。区间包含 0，不能据此替换默认解码。
- 单参考 chrF 对语序很敏感：`legal-01` 的 greedy 译文“除非任何一方发出书面通知，否则协议仍然有效。”与参考完全一致（chrF 100），beam=4 输出“协议仍然有效，除非任何一方发出书面通知。”语义等价但 chrF 降到 68.54。这说明开发集上的小幅 chrF 差异不足以证明质量稳定提升。
- **结论：不实现 beam/惩罚/屏蔽作为默认解码。** 既没有重复/截断下降（已为 0），也没有稳定 chrF 提升，增加 2–5 倍耗时没有依据。

### 实际采用的低风险改动：输出后处理

解码不变，只对模型输出做显示规范化（Python 与 Kotlin 同一规则）：

- 合并连续空白，去掉 CJK 字符两侧空格；
- 紧邻 CJK 的 ASCII 逗号/分号/冒号/问号/叹号转为全角，CJK 后的句点转为“。”；小数点、`9:30`、`a.m.` 等保持 ASCII；
- 去掉 ASCII 标点前空格，合并空行；
- 句末缺标点时补“。”；
- **不改写数字、拉丁词和专名**；数字序列保留检查在报告中逐句记录（40/40 通过；时钟值允许 `9:30`→`9时30分` 这类重新标点）。
- 质量守门：androidTest `OfflineLanguageTest` 新增固定探针，检查无连续重复片段、句末完整、数字/否定保持；1 ms 期限、超长源输入和取消都必须抛错，不能返回部分译文；另有后处理不改变数字/拉丁词的断言。

在同一 40 句开发集上，后处理把 greedy 的平均 chrF 从 35.35（原始 token 串）提到 39.11，且零额外推理耗时；这是标点/空格规范化带来的措辞接近度变化，不是语义准确率提升，也不能修复下面的模型错误。

### 仍存在的失败（保留原始输出）

- `The court dismissed the claim without prejudice.` → “法院在无损于要求的情况下驳回了该要求。”没有表达“不影响再次起诉”的法律含义。
- `The build failed because a dependency was not available offline.` → “建设失败，因为无法离线使用依赖关系。”`dependency` 被译成“依赖关系”。
- `The medicine should not be taken with alcohol.` → “药不应该用酒精。”漏掉“一起服用”。
- `The shop closes at six. Orders placed later will be processed tomorrow.` → “店6点关门晚点下的命令明天会处理。”`orders` 被译成“命令”，多句被合并。
- `A cache miss increases the latency of this request.` → “缓存错误会增加此请求的延迟度。”`cache miss` 仍被译成“缓存错误”，是模型层错误，解码变体无法修复。

这些失败说明：本轮的“质量提升”实际是输出格式稳健化与端到端完整性守门，不是翻译准确率提升。下一步若要实质改善语义，需要更好的离线模型或可靠术语约束，而不是继续堆解码参数；本开发集也不足以宣称准确率。
