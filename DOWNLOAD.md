# 下载 Annota

> **1.0.6-preview · Beta 测试版**（versionCode 18）。本轮不是正式稳定版；沿用现有证书，可覆盖升级。按维护者要求提供给默认更新检查，最近正式版仍为 1.0.2。

[**下载 1.0.6-preview Beta APK**](https://github.com/dpan538/Annota/releases/download/v1.0.6-preview/Annota-1.0.6-preview.apk) · [发布说明](https://github.com/dpan538/Annota/releases/tag/v1.0.6-preview) · [SHA-256](https://github.com/dpan538/Annota/releases/download/v1.0.6-preview/SHA256SUMS.txt) · [真机测试清单](docs/BETA-1.0.6-PREVIEW-CHECKLIST.md)

[下载 1.0.2 正式版](https://github.com/dpan538/Annota/releases/download/v1.0.2/Annota-1.0.2.apk)。已安装较高 versionCode 的用户不能直接降级覆盖，不要为了回退卸载应用。

APK 约 **164 MiB**，包含全部离线词库；安装后无需另行下载语言包。

## 安装与更新

1. 在原版设置中导出 `.annota.zip` 本地备份。
2. 在「设置 → 关于 → 应用更新」检查版本，或下载上方 APK。
3. 允许浏览器 / 文件管理器安装应用，按系统提示覆盖安装，无需卸载或注册账号。
4. 在主页和关于页确认 **1.0.6-preview / Beta**，检查旧文档、批注、书签和阅读进度。

保留原安装证书，versionCode 为 **18**，可从正式版 1.0.2（13）、旧 Beta 1.0.3-test（14）、1.0.4-preview（16）及 1.0.5-preview（17）覆盖升级。检查、下载和安装由用户主动操作。后续正式版须使用大于 18 的 versionCode。

## 本轮变化

- 目录侧栏连续展开与收起，阅读区同步调整宽度；PDF 打开 / 返回使用双向滑动，移除整屏淡入淡出。
- 铅笔黑色加深，保留纸纹、压感与重复涂画；旧笔迹按保存的版本绘制。
- 英汉词库扩充为 771,180 条记录，增加未收录组合词组的离线分段释义、词形还原；词库全部随 APK 提供。
- README 重构为中英文独立产品主页，版本记录和验收证据移至文档。

原有阅读、书写、手写签名、离线查词、文内搜索、导入导出、本地备份及桌面小组件继续保留。没有账号或云同步。仅用户主动更新和提交反馈使用应用网络功能。

本版本使用非调试 Release 构建，但产品状态明确为 **Beta**。自动化与真机测试分别记录，尤其需要验证真实课件的缩略图、放大清晰度和手写笔手感。见[本轮记录](docs/1.0.6-PREVIEW-NOTES.md)。
