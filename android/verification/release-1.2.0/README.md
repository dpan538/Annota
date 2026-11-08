# 1.2.0 正式版验证记录

- 版本：1.2.0 / versionCode 22，稳定通道，沿用原安装证书。
- 构建：`assembleDebug`、`assembleDebugAndroidTest`、`lintDebug`、`assembleRelease`（0 errors / 100 warnings）。
- 仪器化回归：194 / 194 通过（断网 API 36 ARM64 模拟器，288.8 秒）；原始输出见 `regression.txt`。
  一次较早的全量运行出现过 1 项时序敏感的 `InteractionMotionTest` 失败，单独重跑 3 次均通过；
  两次记录都保留，未放宽断言。
- 词典专项：`DictionarySearchUpgradeTest` 11 项、词典相关 88 项、Python `test_lookup.py` 16 项、
  `test_lexicons.py` 20 项通过；长选区与 Bloom 实测数据见 `validation.json`、
  `dictionary-search-latency.json`。
- APK：`apk-archive.json`（资源/SHA-256/签名/覆盖安装/冷启动，loopback 传输）；公开 GitHub 下载验证见
  `public-apk.json` 与 `release.json`。
- 发布正文：`release-body.md`。

模拟器结果不替代 Xiaomi Pad 9 Pro Max 的手感、帧率与内存验收。
