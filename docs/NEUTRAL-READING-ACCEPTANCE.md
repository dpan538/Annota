# 阅读颜色与 README（1.0.1 / versionCode 12）

1.0.0 已正式发布，本修正以 1.0.1 补丁覆盖升级，保留原版本附件和证书。

## 原因与修正

旧 SOFT 的白点为 `#FAF7EF`，外围背景为 `#EEECE6`，黑点为 `#191C20`；新文档默认选择 SOFT，导致 PDF 阅读界面整体偏暖黄。

改为中性白点 `#F8F8F8`、外围 `#EEEEEE`、黑点 `#191919`，同亮度灰线不再发生通道偏移。新文档默认 ORIGINAL 原始白底，既有 SOFT 选择继续有效。三个预设数量不变；不改源 PDF、笔迹或导出内容。

## 验收

- `neutral-reading-native-api36.txt`：6 项阅读回归通过，覆盖原始颜色可逆性、灰线保留、彩色图表区分、只读输入保护与按文档状态保持。
- `neutral-reading-local-apk-check.json`：非调试候选资源、签名、安装与冷启动通过，沿用既有证书。
- `neutral-reading-gallery-api36.txt`：实际非调试 1.0.1 的产品浏览截图流程通过。`neutral-reading-gallery.json` 记录截图哈希与条件；原始模式白纸采样为 RGB 255/255/255，柔和为 248/248/248，均无黄偏。
- README 使用 `docs/images/` 中六张同蓝调截图，按书库、阅读、笔菜单、词典、页面和文内搜索说明用途。七张原始截图均来自应用，未改色或拼接界面。

公开下载、版本附件与系统覆盖升级结果在发布后补充。历史 1.0.0 交付详见 [首版验收](INTEGRATION-ACCEPTANCE.md)。
