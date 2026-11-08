# 1.2.1 候选验证记录（未发布）

**这不是发布记录。** 本目录记录开发候选 1.2.1 / versionCode 23 的本地验证：
没有创建 GitHub Release、没有上传 APK、没有更新 `annota-update.json`；
默认更新通道仍是公开的 1.2.0。

- 构建：`assembleDebug`、`assembleDebugAndroidTest`、`lintDebug`、`assembleRelease` 通过；
  lint 0 errors / 100 warnings。
- 仪器化回归：**271 / 271 通过**（断网 API 36 ARM64 模拟器，302.1 秒，原始输出 `regression.txt`）。
  `ThumbnailNavigationTest` 需要 400+ 页讲义夹具；本次使用生成的 420 页合成 PDF，通过 `run-as`
  推送，不含用户内容，未提交到仓库。
- 词典专项：`DictionarySearchUpgradeTest` 13/13；缩写 `AbbreviationLookupTest` 7/7；领域
  `SenseDomainRankingTest` 7/7；Python `test_lookup.py` 18 项、`test_lexicons.py` 20 项通过。
- 手写：P1 `InkPerformanceTest` 4/4；P3 `InkFountainPenTest` 11/11；书写体验 IEA `ContactIntentTest` 4/4
  + `ArmGuardTest` 1/1，ITL `InkLedgerTest` 6/6，SCR `StrokeContinuityTest` 7/7，NPDE `InkDynamicsTest` 12/12，
  PRC `InkPredictorTest` 8/8，验收 `WritingExperienceAcceptanceTest` 3/3。
- 数据：en-zh 771,180 条、aliases 682；zh-en 238,664 条（+108,863 反向条目）；wordnet 95,221 条；
  wiktionary 59,290 条；语义向量 101,085；Bloom key 1,316,131（抽样 500 无假阴性，假阳性 8/1000）。
- 体积与启动：词库资产约 386 MiB；候选 APK **412,286,600 字节（约 393.2 MiB）**，
  SHA-256 `e571c47b…2755f`，证书沿用 `90e5c38e…be18`；首次冷启动 **1,040 ms**（含并行解压）。
- 验收可视化：`acceptance/README.md`；图表见 `acceptance/chart-*.png` 与 `acceptance/ink-before-after.png`。
- 翻译：40 条自写开发集上 greedy 后处理 chrF 39.11（原始 35.35）；beam4 为 40.70 但 3.6× 耗时且
  配对区间含 0，未采用；新增 4 项守门测试。详见 `design/translation/evaluation/variant-report.json`。
- 候选更新清单只保存在本目录（`annota-update-candidate.json`），未发布。
- APK 身份/资源/签名/安装/冷启动记录：`apk-candidate.json`。

模拟器结果不替代 Xiaomi Pad 9 Pro Max 的手感、帧率与内存验收。
