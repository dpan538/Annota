# 1.1.1 词义排序验证（向量集成前阶段）

2026-11-04。本目录记录向量集成前的词义排序阶段。最终 1.1.1 正式版含新增向量优化，交付及最终回归见 [release-1.1.1](../release-1.1.1/) 与 [semantic-111](../semantic-111/)。受测环境为专用 Android 16 / API 36 ARM64 模拟器 `emulator-5584`，不是 Xiaomi Pad 9 Pro Max 真机。测试时关闭模拟器 Wi-Fi 与移动数据。

## 变更

- 新增独立 Kotlin `SenseRanker`：借鉴 Lesk 的定义与语境重叠思想，自实现候选局部 IDF、距离权重、长度抑制、词形证据合并与明确拒判。未复制第三方源码，不新增运行时依赖。
- Wiktionary 和 WordNet 的已加载义项统一竞争，保留中文相同但英文定义不同的候选。一次排序共用只读 SQLite 连接及词形缓存。
- 否定或排除语境、限定词短语与动词义冲突阻止强行提升；领域 / 动词提示仅影响默认排序。长选区分段不借用相邻句子证据。
- 上游自动词组扩展至少需要两个实质词；已确认的人工规则义项不被未复核上游词组替换。主动查完整词组及应用内已核对词组保持可用。
- 本轮不修改词库资产、不新增特定课件词条或规则。所有处理仍在本地，数据随 APK 分发。

## 自动化与产物

最终状态、APK SHA-256、签名、构建 / lint 和测试数量见 [validation.json](validation.json)；原始测试输出见 [regression-final.txt](regression-final.txt)，产物核验见 [apk-check.json](apk-check.json)。APK 仅在专用模拟器覆盖安装；HTTP 校验使用回环服务器，不是公开下载或发布验证。

测试包括纯排序器反例、跨库候选保留、英文定义完整展示、断句上下文、歧义与否定拒判、真实 PDF 取词 UI、已有词库覆盖、离线语言及检索回归。诊断句逐条输出，不用“有结果”断言代替语义准确性。

## 语义复核

[36 条最终诊断](diagnostics-final.json)记录首释义、展开项、完整候选、POS、英文 gloss、来源与 context 标记。[研究审阅](../../../design/dictionary/evaluation/final-semantic-review.json)逐条区分正确、部分正确、默认排序不合语境和来源缺口。审阅输入的不可变副本为 [diagnostics-reviewed.json](diagnostics-reviewed.json)；最终重跑与副本的语义字段一致性记录在 validation.json。

这些句子由研究任务编写，经过本轮调试与代理语义复核，**不是独立人工金标，也不是词典总体准确率**。`noContextPrimary` 是本次代码关闭上下文的结果，不是上一版本基线；关键词命中不代表释义正确。已有人工规则与整词组查得的结果不能归功于通用 Lesk 排序。

明显改善包括农田语境不再自动替换成 `in the field` 的作战义；排除金融机构的 bank 例句不再标为已确认“银行”。后者仍可能把普通默认“银行”放在“河岸”前，只能算减少错误提升，不能算完成正确消歧。

仍有默认义不合语境和上游汉译不精确，例如 prison cell、water current、统计 significant，以及 culture 的活动 / 实体区分。来源质量、候选缺口和复杂句法需要继续处理；评分不是概率，不宣称通用消歧已完成。来源审计和后续候选方案见 [研究报告](../../../design/dictionary/RESEARCH-SENSE-RANKING.md)。

## 保留失败过程

- `regression-initial.txt`：55 项，1 项失败；技术领域默认排序门禁过严，影响 scaling。
- `regression-refinement.txt`：61 项，5 项失败；其中包含上述默认排序回退，以及改变词组展示后旧完整字符串断言不符。
- `regression-domain-restored.txt`：61 项，1 项失败；上游 battery cell 的“电池组电池”覆盖可靠“电池单元”，修复词组优先级，而非放宽该断言。
- `regression-ui-expectation.txt`：61 项，1 项失败；优先级修复后真实 UI 已恢复“河岸”，本轮中途改为“河岸；河堤”的期望过时，恢复原期望。
- 最终回归重新运行完整受影响套件。中间诊断、来源审计与审阅均保留，不只挑选成功案例。

查词时间为模拟器进程内连续诊断的样本耗时，包含缓存效果；不代表真机冷启动 p95。尚需目标平板实测及未参与调参的独立语义评估。
