## Annota 1.2.0 · 正式版

versionCode **22**，沿用原安装证书，可从 1.1.1 正式版及 Preview 覆盖升级。适配目标为 **Xiaomi Pad 9 Pro Max**，Android 10+ / ARM64。

### 本地词典检索升级

- 修正 Wiktionary 把 “Translations” 等章节标题当作词义的问题（移除 1,786 条伪义项）：`cut` 不再以“卡”为首义，`bra` 不再出现“左矢”；义项顺序改为构建期固定的 `sense_order`。
- 合并 Chinese WordNet（CWN）与 OMW Wiktionary 中文词网，按 synset/ILI 对齐并入 WordNet；英文释义采用可对齐的 Open English WordNet 2025。合并后 **50,639 synset / 95,221 条英中词义关联 / 72,948 词头**。
- 汉英库新增 3,529 个 CC-CEDICT 未收录的中文词条与 4,148 条 OpenCC 繁→简单字映射，繁体查询自动扩展为简体。
- 检索性能：持久只读连接、查询与负结果缓存、Bloom 位图预筛、Myers 位并行编辑距离、ECDICT 词频排序。105 词长选区在断网模拟器上首次约 163–243 ms、重复约 48–66 ms（1.1.1 同用例约 1.1–1.6 秒）。
- 修正混合语境下词义排序过度自信：人工规则提示两个主题冲突时不再由词面重叠单独选出首义。

词库、模型和索引全部随 APK 安装，首次使用不需要下载；阅读、书写、查词及参考翻译均在本机处理。只有主动更新或提交反馈使用网络。

### 验证与范围

自动化回归 194 / 194 通过（断网 API 36 ARM64 模拟器）；词库构建两次运行哈希一致。构建、lint 与 APK 资源/签名检查通过；公开附件下载与 SHA-256 校验完成后本版才作为默认更新入口。

CWN 与 OMW 中文词义未经逐条人工校对；合并条目数不能与主词库相加。繁简扩展为逐字映射；OEWN 中无法经 CILI 对齐的 3,184 个 synset 不进入索引。模拟器结果不代表目标平板的手感、帧率或内存验收。

### 下载与升级

下载附件 **Annota-1.2.0.apk**，无需卸载旧版。APK 约 **477 MiB**，包含离线模型与词库；建议升级前导出 `.annota.zip` 备份。安装包、SHA256SUMS.txt 与 annota-update.json 一同发布，默认更新入口提供本正式版。

[完整更新与真机复核清单](https://github.com/dpan538/Annota/blob/v1.2.0/docs/1.2.0-RELEASE-NOTES.md) · [检索升级记录](https://github.com/dpan538/Annota/blob/v1.2.0/design/dictionary/SEARCH-UPGRADE.md) · [测试记录](https://github.com/dpan538/Annota/tree/v1.2.0/android/verification/release-1.2.0)
