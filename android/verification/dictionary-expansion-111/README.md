# 1.1.1 离线词典扩充验证

日期：2026-11-04。**候选未发布**；公开版本和更新入口保持 1.1.0。源码推送不代表 APK 发布。

## 环境与范围

Android 16 / API 36 ARM64 专用模拟器 `emulator-5584`，Wi-Fi 与移动数据关闭；不使用目标平板或其他模拟器。测试词句为合成材料，不包含私人 PDF。实际英文释义面板截图见 [english-definition-panel-111.png](english-definition-panel-111.png)。

新增最终数据库包含 192,058 个英文释义词头、208,999 条英文释义、338,228 个来源词形关系；其中 203,325 个关系指向已有中文释义词头。**英文释义不是新增汉译**，词形关系也不是新增词义。数量及数据库哈希见 [lexicon-coverage.json](lexicon-coverage.json)。

## 检查结果

| 检查 | 结果与证据 |
| --- | --- |
| Python 提取、过滤及查询回归 | 23 项通过：[python-tests.txt](python-tests.txt) |
| Android 首轮广泛回归 | 141 项运行，139 项通过、2 项断言失败；原始失败保留：[regression.txt](regression.txt) |
| Android 最终词典与翻译专项 | 20 项全部通过，包括上述修正、长选区延迟与取消：[final-language.txt](final-language.txt) |
| 构建及静态检查 | Debug、测试 APK、Release 构建通过；lint 为 0 错误、96 条既有警告 |
| 资源完整性 | 数据库与模型哈希、ZIP CRC 校验通过；13 个来源资产校验见 [source-assets.json](source-assets.json) |
| 候选安装包 | 同证书覆盖安装、冷启动通过：[apk-check.json](apk-check.json)。仅通过临时回环 HTTP 验证传输，不是公开下载测试 |

首轮两条失败均由数据核验纠正了测试预期：`systems analyses` 有明确的整体原形 `systems analysis`，不应强制选择另一候选；`immunophenotyping` 可经原形命中“免疫表型”，不应要求英文兜底。重测还发现 Debug 测试访问 Release 中 `internal` 函数的名称不一致，已将纯候选生成函数公开，见 [retest-initial.txt](retest-initial.txt)。没有按样例硬编码生产释义。

最终专项覆盖 20 项；首轮另有不属于这四个专项类的 123 项通过。它们合计覆盖 143 个不同用例，**不是一次完整 143 项测试运行**。最终 APK 与专项测试时的生产代码一致，随后仅修正来源说明中的 APK 资产路径并重新构建、校验与覆盖安装。

## 延迟与翻译边界

[查询抽样](dictionary-query-latency-111.json)中短词为 1–10 ms，约百词长选区为 1,265–1,437 ms；只覆盖两轮合成查询，且不包含首次词库安装。这是模拟器数据，不是目标平板性能结论。

[多句输出](offline-translation-passages-111.json)验证了完整句分段和整体返回；[原有短句输出](offline-translation-111.json)保留独立模型运行结果。测试覆盖取消、超限和完整性，**不证明译文语义准确**。专业术语仍有已记录错误，详见 [32 项跨领域探针与研究](../../../design/translation/RESEARCH-NEXT.md)。未知单词不会交给模型猜译。

所有词库及模型随 APK 打包，运行时不下载。最终候选约 396.2 MiB，新增数据带来包体和解包存储开销；目标平板的首次安装、长段落耗时、发热仍需复核。真机清单见 [待发布说明](../../../docs/1.1.1-RELEASE-NOTES.md)。
