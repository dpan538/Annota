# 下载 Annota

**1.2.0 · 正式版**（versionCode 22）。沿用原有安装证书，可从 1.1.1 正式版及 Preview 覆盖升级。

[**下载 1.2.0 APK**](https://github.com/dpan538/Annota/releases/download/v1.2.0/Annota-1.2.0.apk) · [GitHub Release](https://github.com/dpan538/Annota/releases/tag/v1.2.0) · [SHA-256](https://github.com/dpan538/Annota/releases/download/v1.2.0/SHA256SUMS.txt) · [更新说明](docs/1.2.0-RELEASE-NOTES.md)

APK **500,449,037 字节，约 477.3 MiB**，包含词库、词形与词义索引、MiniLM 语义模型及 OPUS-MT 参考翻译模型。首次使用无需下载语言包或模型。附件与校验值见正式 Release。

## 安装与更新

1. 在原版设置中导出 `.annota.zip` 本地备份。
2. 在「设置 → 关于 → 应用更新」检查版本，或下载上方 APK。
3. 按系统提示允许安装并覆盖升级，无需卸载或注册账号。
4. 在主页与关于页确认 **1.2.0 / 正式版**。原文档、批注、书签和阅读进度应保留。

**适配目标为 Xiaomi Pad 9 Pro Max；Android 10+ 是安装要求。** 默认更新入口提供 1.2.0 正式版，检查、下载和安装由用户主动操作。旧版 versionCode 较低，不要为了回退卸载应用。

本版修正 Wiktionary 章节标题被当作词义的问题，合并 Chinese WordNet、OMW Wiktionary 中文词网与 Open English
WordNet 2025，新增繁体查询扩展与 CC-CEDICT 未收录的 3,529 个中文词条；检索改用持久只读连接、Bloom
位图预筛、Myers 编辑距离与词频排序。长选区分段在模拟器上由约 1.1–1.6 秒降到重复约 50–70 毫秒。
词库、模型与词义向量仍全部随 APK 提供，首次使用和飞行模式均不需要下载。

「设置 → 关于 → 本地词典」默认开启，位于应用更新下方；只切换语义重排和后台预处理，关闭后普通离线查词仍可使用。

验证范围与真机复核清单见[发布记录](docs/1.2.0-RELEASE-NOTES.md)，不把模拟器结果作为目标设备手感验收。
