# 下载 Annota

**1.1.1 · 正式版**（versionCode 21）。沿用原有安装证书，可从旧正式版及 Preview 覆盖升级。

[**下载 1.1.1 APK**](https://github.com/dpan538/Annota/releases/download/v1.1.1/Annota-1.1.1.apk) · [GitHub Release](https://github.com/dpan538/Annota/releases/tag/v1.1.1) · [SHA-256](https://github.com/dpan538/Annota/releases/download/v1.1.1/SHA256SUMS.txt) · [更新说明](docs/1.1.1-RELEASE-NOTES.md)

APK **488,423,888 字节，约 465.8 MiB**，包含词库、词形与词义索引、MiniLM 语义模型及 OPUS-MT 参考翻译模型。首次使用无需下载语言包或模型。附件与校验值见正式 Release。

## 安装与更新

1. 在原版设置中导出 `.annota.zip` 本地备份。
2. 在「设置 → 关于 → 应用更新」检查版本，或下载上方 APK。
3. 按系统提示允许安装并覆盖升级，无需卸载或注册账号。
4. 在主页与关于页确认 **1.1.1 / 正式版**。原文档、批注、书签和阅读进度应保留。

**适配目标为 Xiaomi Pad 9 Pro Max；Android 10+ 是安装要求。** 默认更新入口提供 1.1.1 正式版，检查、下载和安装由用户主动操作。旧版 versionCode 较低，不要为了回退卸载应用。

本轮修复取词菜单滞留和阅读顶部栏空白占位，并扩充离线词形、Wiktionary 及英文释义。语义重排完成后再显示释义；已计算的语境向量保留在本机。英文长词组 / 短句提供离线参考译文，可能存在歧义和专业词误译。阅读、批注、查词和参考翻译均在本地处理；仅主动更新和提交反馈使用网络。

「设置 → 关于 → 本地词典」默认开启，位于应用更新下方；只切换语义重排和后台预处理，关闭后普通离线查词仍可使用。

验证范围与真机复核清单见[发布记录](docs/1.1.1-RELEASE-NOTES.md)，不把模拟器结果作为目标设备手感验收。
