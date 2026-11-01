# 下载 Annota

> **1.0.5-preview · Beta 测试版**（versionCode 17）。本轮不是正式稳定版；沿用现有证书，可覆盖升级。按维护者要求提供给默认更新检查，最近正式版仍为 1.0.2。

[**下载 1.0.5-preview Beta APK**](https://github.com/dpan538/Annota/releases/download/v1.0.5-preview/Annota-1.0.5-preview.apk) · [发布说明](https://github.com/dpan538/Annota/releases/tag/v1.0.5-preview) · [SHA-256](https://github.com/dpan538/Annota/releases/download/v1.0.5-preview/SHA256SUMS.txt) · [真机测试清单](docs/BETA-1.0.5-PREVIEW-CHECKLIST.md)

[下载 1.0.2 正式版](https://github.com/dpan538/Annota/releases/download/v1.0.2/Annota-1.0.2.apk)。已安装较高 versionCode 的用户不能直接降级覆盖，不要为了回退卸载应用。

## 安装与更新

1. 在原版设置中导出 `.annota.zip` 本地备份。
2. 在「设置 → 关于 → 应用更新」检查版本，或下载上方 APK。
3. 允许浏览器 / 文件管理器安装应用，按系统提示覆盖安装，无需卸载或注册账号。
4. 在主页和关于页确认 **1.0.5-preview / Beta**，检查旧文档、批注、书签和阅读进度。

保留原安装证书，versionCode 为 **17**，可从正式版 1.0.2（13）、旧 Beta 1.0.3-test（14）及 1.0.4-preview（16）覆盖升级。检查、下载和安装由用户主动操作。后续正式版须使用大于 17 的 versionCode。

## 本轮变化

- 画近似直线后停笔约 0.65 秒自动拉直，抬笔前仍可调整终点；短划、弯折和圈线不会强行转成直线。
- 框选新增旋转手柄，支持笔迹、形状、图片和签名；旋转可保存、撤销 / 重做与导出，PDF 原文不参与编辑。
- 新铅笔笔迹使用细淡纹理，旧笔迹保持原样；移除工具悬停 / 点击时出现的系统矩形色块。
- 缩略图使用独立渲染资源，清理离屏任务；正文先补齐可见页底图再细化分块。文档打开 / 返回、侧栏与菜单加入短过渡。
- 所有词库随 APK 安装与更新，保持完全离线；无 Google 翻译 SDK、模型下载或相关提示。

原有阅读、书写、手写签名、离线查词、文内搜索、导入导出、本地备份及桌面小组件继续保留。没有账号或云同步。仅用户主动更新和提交反馈使用应用网络功能。

本版本使用非调试 Release 构建，但产品状态明确为 **Beta**。自动化与真机测试分别记录，尤其需要验证真实课件的缩略图、放大清晰度和手写笔手感。见[本轮记录](docs/1.0.5-PREVIEW-NOTES.md)。
