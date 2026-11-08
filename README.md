# Annota

[中文](README.md) · [English](README_EN.md)

**打开文档，专注阅读，随手记录。**

本地 PDF 阅读与手写批注应用，围绕 **Xiaomi Pad 9 Pro Max** 优化。无需账号，文档、笔迹、阅读进度和查词都留在设备上。

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84)](DOWNLOAD.md)
[![Stable](https://img.shields.io/badge/Stable-1.2.0-16A34A)](https://github.com/dpan538/Annota/releases/tag/v1.2.0)
[![Preview](https://img.shields.io/badge/Preview-1.2.1--preview.1-C2410C)](https://github.com/dpan538/Annota/releases/tag/v1.2.1)
[![License](https://img.shields.io/badge/License-MIT-8B5CF6)](LICENSE)
![Offline](https://img.shields.io/badge/Dictionary-Offline-0891B2)
![Local](https://img.shields.io/badge/Storage-On_device-DB2777)
![No account](https://img.shields.io/badge/Account-Not_required-64748B)
![Device](https://img.shields.io/badge/Xiaomi-Pad_9_Pro_Max_only-FF6900)

[**下载 1.2.1-preview.1 预览版 APK**](https://github.com/dpan538/Annota/releases/download/v1.2.1/Annota-1.2.1.apk) · [下载 1.2.0 正式版 APK](https://github.com/dpan538/Annota/releases/download/v1.2.0/Annota-1.2.0.apk) · [安装说明](DOWNLOAD.md) · [更新日志](CHANGELOG.md)

> **Preview 说明**：1.2.1-preview.1 是已作为普通 Release 发布的预览版，包含新的书写/墨迹系统；真机验收仍在进行。需要最稳定版本请安装 1.2.0。两者使用同一安装证书，可覆盖升级。

[特色](#特色) · [快速开始](#快速开始) · [功能预览](#功能预览) · [开发](#开发) · [许可](#许可)

## 特色

- **专注阅读** — 原始 PDF 分块渲染、连续滚动、双指缩放与相邻双页；文内搜索、目录、书签和阅读位置恢复。
- **自然批注** — 笔、铅笔、钢笔与荧光笔；压感、停笔拉直、水平吸附直尺，框选移动 / 缩放 / 旋转，撤销与重做。支持手写签名及 PDF 导出。
- **离线语言工具** — 英汉 / 汉英词库结合 ECDICT、Wiktionary、WordNet、Chinese WordNet 与 Open English WordNet，支持专业词、复合词、繁简互查和英文释义兜底。MiniLM 在设备上比较语境与候选词义；英文长词组和短句可查看 OPUS-MT 离线参考译文。全部随 APK 安装。
- **本地书库** — 文件夹、收藏、搜索与废纸篓；接收其他应用分享的 PDF，本地 `.annota.zip` 备份与恢复，桌面小组件继续阅读。

本页对应 **1.2.0 正式版** 与 **1.2.1-preview.1 预览版**。[版本差异与已知范围](CHANGELOG.md)。

## 快速开始

1. 安装 APK，打开随包的 *Attention Is All You Need* 或导入自己的 PDF；首次使用可查看内置指南。
2. 点击笔图标开始批注，阅读模式收起批注工具；长按文字可复制或离线查词。
3. 内容自动保存在本机。需要带走或备份时，导出 PDF 或完整备份包。

**适配目标：Xiaomi Pad 9 Pro Max。** Android 10+ 是安装要求，不代表其他设备已完成适配。扫描件可以阅读和批注；选词与搜索需要 PDF 文本层。暂不提供 OCR、iPad 版、云同步或证书数字签名。

## 功能预览

实际应用截图，统一使用蓝调外观；点击图片可看大图。

| 本地书库 | 画笔与批注 |
| --- | --- |
| [![书库](docs/images/readme-library-blue.png)](docs/images/readme-library-blue.png) | [![画笔菜单](docs/images/readme-pen-blue.png)](docs/images/readme-pen-blue.png) |
| 导入文件，按文件夹与收藏整理。 | 在原文上记录，切换工具保留阅读位置。 |

| 离线查词 | 页面导航 |
| --- | --- |
| [![离线查词](docs/images/readme-dictionary-blue.png)](docs/images/readme-dictionary-blue.png) | [![页面导航](docs/images/readme-pages-blue.png)](docs/images/readme-pages-blue.png) |
| 就地查看简释，更多义项按需展开。 | 缩略图、目录、书签与批注集中在侧栏。 |

## 本地词典

查词先完成候选词义的语境比较，再显示释义；证据不足时保留其他解释，不把相似度当作确定答案。中文释义未命中时可显示明确标注的英文释义；繁体查询会自动扩展为简体再检索；短句参考翻译仅支持**英文 → 简体中文**，专业内容仍需核对。

词义索引在 WordNet 3.0 与 Chinese Open WordNet 之上，合并 Chinese WordNet 与 OMW Wiktionary 中文词网，并采用可对齐的 Open English WordNet 2025 英文释义：72,948 个英文词头、95,221 条英中词义关联。查词使用本机只读连接、Bloom 位图预筛、词频建议排序与分段缓存，书写或翻页时不反复打开词库。

打开 PDF 后，在后台处理当前已提取页面的语境窗口。语境向量保存在本机，按内容哈希、模型版本和算法版本复用，避免每次重复计算。词库、模型与词义向量均随 APK 更新，首次使用也不下载。

「设置 → 关于」中，应用更新下方的**本地词典**默认开启。该开关只控制语义重排与后台预处理；关闭后仍可普通离线查词。详见[本地数据说明](docs/PRIVACY.md)。

## 数据与更新

阅读、书写、查词及参考翻译完全离线；仅主动检查应用更新、下载 APK 或提交反馈使用网络。反馈会提交为公开 GitHub Issue，请勿附上私人内容。卸载或清除应用数据前，请先备份。

**1.2.0 为正式版，1.2.1-preview.1 为预览版**。应用内更新检查会看到最新发布的 Preview（versionCode 24）；不想要预览版的用户请手动安装/保留 1.2.0。下载和安装均需用户确认，沿用已有安装证书，可覆盖升级。

## 原创功能知识库（供人/机器检索）

Annota 的书写与离线词典系统有独立、可验证的设计记录。每个功能页固定包含：问题、机制、与现有方案的差异、可量化证据、验证范围/未验证项、限制、代码路径。

- **功能索引（网页）**：<https://dpan538.github.io/Annota/>
- **机器可读功能表**：[features.json](https://dpan538.github.io/Annota/features.json)
- **LLM 索引**：[llms.txt](https://dpan538.github.io/Annota/llms.txt) · [仓库 llms.txt](llms.txt)
- **发布元数据**：[release.json](https://dpan538.github.io/Annota/release.json)（stable/preview、APK URL、SHA-256）
- **爬虫规则**：[robots.txt](https://dpan538.github.io/Annota/robots.txt) · [sitemap.xml](https://dpan538.github.io/Annota/sitemap.xml)

| 原创功能 | 解决的具体问题 | 证据摘要 |
| --- | --- | --- |
| [Contact-Intent Edge Guard](https://dpan538.github.io/Annota/features/contact-intent-edge-guard.html) | 手臂大面积接触屏幕边缘被系统当成返回手势，误退出阅读器 | 合成“前臂+返回”：基线退出，候选保持阅读器 |
| [Ink Transaction Ledger](https://dpan538.github.io/Annota/features/ink-transaction-ledger.html) | 抬笔同步整本序列化阻塞 UI，快写断触 | UI submit 0.024 ms；抬笔 11.41→7.14 ms；CANCEL 保笔 |
| [Stroke Continuity Reconstruction](https://dpan538.github.io/Annota/features/stroke-continuity.html) | 输入丢包造成连笔中断 | 圆弧删 5 点误差 0.001 pt；连笔 0.315 pt |
| [Ink-Flow Dynamics v6](https://dpan538.github.io/Annota/features/ink-dynamics-v6.html) | 连笔接缝细、阻尼不足 | 接缝 100.1%；3 pt 小环 98.6%；v4/v5 位级不变 |
| [Predict–Reconcile Ink](https://dpan538.github.io/Annota/features/predict-reconcile.html) | 跟手性与预测过冲的矛盾 | 1 帧误差 0.571 px；快速折返不过冲 |
| [Page-Picture Ink Cache](https://dpan538.github.io/Annota/features/page-picture-cache.html) | 长页每帧逐笔重绘 | 稳态帧 2.0–2.9×；像素差 0 |
| [Offline Context Dictionary](https://dpan538.github.io/Annota/features/offline-context-dictionary.html) | 离线缩写、数字缩合与语境选义 | 40 轮稳态中位数 10.17→4.92 ms；缩写 7/7 |

> 这些是工程证据，不是准确率/营销承诺。模拟器结果与真机验收分开记录；未验证项在每页明确标注。

## 开发

原生 Kotlin / Android Views，系统 PdfRenderer、PDFBox 与本地 SQLite。需要 JDK 17 / 21、Android SDK 36、Build Tools 36.0.0。

```sh
git clone https://github.com/dpan538/Annota.git
cd Annota/android
./gradlew :app:assembleDebug
```

[Android 工程与签名](android/README.md) · [空间与归档](docs/PROJECT-STORAGE.md) · [测试与验收](docs/testing.md) · [离线词库构建](design/dictionary/README.md) · [设计原型](design/README.md) · [反馈问题](https://github.com/dpan538/Annota/issues)

## 许可

代码：[MIT](LICENSE)。原创视觉与指南：[CC BY-NC 4.0](LICENSE-VISUALS.md)。字体、图标、词库及示例论文遵循各自许可，见[第三方说明](THIRD_PARTY_NOTICES.md)。

独立开发，与 Xiaomi 没有隶属或官方合作关系。
