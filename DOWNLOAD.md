# 下载 Annota

> **1.0.7-preview · Beta 测试版**（versionCode 19）。本轮不是正式稳定版；沿用现有证书，可覆盖升级。按维护者要求提供给默认更新检查，最近正式版仍为 1.0.2。

[**下载 1.0.7-preview Beta APK**](https://github.com/dpan538/Annota/releases/download/v1.0.7-preview/Annota-1.0.7-preview.apk) · [发布说明](https://github.com/dpan538/Annota/releases/tag/v1.0.7-preview) · [SHA-256](https://github.com/dpan538/Annota/releases/download/v1.0.7-preview/SHA256SUMS.txt) · [真机测试清单](docs/BETA-1.0.7-PREVIEW-CHECKLIST.md)

[下载 1.0.2 正式版](https://github.com/dpan538/Annota/releases/download/v1.0.2/Annota-1.0.2.apk)。已安装较高 versionCode 的用户不能直接降级覆盖，不要为了回退卸载应用。

APK 约 **164 MiB**，包含全部离线词库；安装后无需另行下载语言包。

## 安装与更新

1. 在原版设置中导出 `.annota.zip` 本地备份。
2. 在「设置 → 关于 → 应用更新」检查版本，或下载上方 APK。
3. 允许浏览器 / 文件管理器安装应用，按系统提示覆盖安装，无需卸载或注册账号。
4. 在主页和关于页确认 **1.0.7-preview / Beta**，检查旧文档、批注、书签和阅读进度。

保留原安装证书，versionCode 为 **19**，可从正式版 1.0.2（13）、旧 Beta 1.0.3-test（14）、1.0.4-preview（16）、1.0.5-preview（17）及 1.0.6-preview（18）覆盖升级。检查、下载和安装由用户主动操作。后续正式版须使用大于 19 的 versionCode。

## 本轮变化

- PDF 打开 / 返回改为 320 ms 的短距离位移、轻微缩放与辅助透明度过渡，使用缓动起停；不再高速横跨整屏。目录继续同步调整阅读区宽度。
- 词典保留缩写大小写，增加随 APK 提供的 NIST 术语补充；根据局部语境区分专业与日常释义，点击分段词条保留原文语境。
- 阅读工具栏右侧按钮整体内收；中英文 README 移除 Hero 大图，增加分类彩色标签。

原有阅读、书写、手写签名、离线查词、文内搜索、导入导出、本地备份及桌面小组件继续保留。没有账号或云同步。仅用户主动更新和提交反馈使用应用网络功能。

本版本使用非调试 Release 构建，但产品状态明确为 **Beta**。自动化与真机测试分别记录，尤其需要验证真实课件的缩略图、放大清晰度和手写笔手感。见[本轮记录](docs/1.0.7-PREVIEW-NOTES.md)。
