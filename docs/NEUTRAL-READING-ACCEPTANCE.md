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

[1.0.1 正式版](https://github.com/dpan538/Annota/releases/tag/v1.0.1) 已公开，非草稿、非预发布，latest stable 为 1.0.1。三个附件已匿名核对；APK 完整匿名下载的资源、ZIP、签名与哈希通过。原始公开非调试 1.0.0 已通过应用更新入口与真实 Android 系统安装器升级至 1.0.1，前后文档、源 PDF 哈希、已准备的蓝色矢量笔迹、书签、阅读位置与外观/阅读偏好快照一致，待安装记录已清理。见 `neutral-reading-public-release.json`、`neutral-reading-apk-public-check.json`、`neutral-reading-update-install.json` 与前后快照。

源码身份为 `074d0ff880da5b5b8973fd276d8c80c2c2be63e0`，APK SHA-256 为 `423655f9f0e3ca5920c5e9a4e86528a65c4fc6d8f658c92b95e2ee04aaf39d34`。README 已在 GitHub 实际页面复核，六张图均加载并显示图注，见 `neutral-reading-readme-render.json`。历史 1.0.0 交付详见 [首版验收](INTEGRATION-ACCEPTANCE.md)。
