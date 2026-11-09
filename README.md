# Annota

[中文](README.md) · [English](README_EN.md)

本地 PDF 阅读与手写批注应用，围绕 **Xiaomi Pad 9 Pro Max** 优化。无账号，文档、笔迹、阅读进度和查词都留在设备上。

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84)](DOWNLOAD.md)
[![Stable](https://img.shields.io/badge/Stable-1.2.2-16A34A)](https://github.com/dpan538/Annota/releases/tag/v1.2.2)
[![License](https://img.shields.io/badge/License-MIT-8B5CF6)](LICENSE)
![Offline](https://img.shields.io/badge/Dictionary-Offline-0891B2)

[**下载 1.2.2 正式版 APK**](https://github.com/dpan538/Annota/releases/download/v1.2.2/Annota-1.2.2.apk) · [安装说明](DOWNLOAD.md) · [更新日志](CHANGELOG.md)

> 1.2.2 为当前正式版。

## 功能

- **阅读**：连续滚动、双指缩放、相邻双页、文内搜索、目录、书签、阅读位置恢复。
- **手写**：笔、铅笔、钢笔、荧光笔；压感、直尺、框选移动/缩放/旋转；撤销/重做；手写签名与 PDF 导出。
- **标注**：独立大小与局部/整笔模式的橡皮；图形与图片标注。
- **离线词典**：英汉/汉英查询、专业术语、语境选义、英文释义与英文短句参考翻译，全部随 APK 安装。
- **本地书库**：文件夹、收藏、搜索、废纸篓、PDF 分享导入、完整备份与恢复。

**适配目标：Xiaomi Pad 9 Pro Max。** Android 10+ 是安装要求，不代表其他设备已完成适配。扫描件可阅读和批注，选词与搜索需要 PDF 文本层；暂不支持 OCR、iPad 版、云同步或证书数字签名。

## 快速开始

1. 安装 APK，打开随包的 *Attention Is All You Need* 或导入自己的 PDF。
2. 用工具栏批注；阅读模式收起工具。长按文字可复制或离线查词。
3. 内容自动保存在本机；导出 PDF 或完整备份包即可带走。

## 截图

| 本地书库 | 手写批注 |
| --- | --- |
| [![书库](docs/images/readme-library-blue.png)](docs/images/readme-library-blue.png) | [![画笔菜单](docs/images/readme-pen-blue.png)](docs/images/readme-pen-blue.png) |

| 离线查词 | 页面导航 |
| --- | --- |
| [![离线查词](docs/images/readme-dictionary-blue.png)](docs/images/readme-dictionary-blue.png) | [![页面导航](docs/images/readme-pages-blue.png)](docs/images/readme-pages-blue.png) |

## 离线词典

查词在本机完成：词库覆盖英汉、汉英、专业术语与多来源词义；多义词会结合当前语境排序，证据不足时保留其他解释。中文释义未命中时显示明确标注的英文释义；繁体查询自动扩展简体。英文短句可查看参考翻译（英→简中），专业内容仍需核对。

词库、模型和词义向量随 APK 更新，首次使用不下载。可在「设置 → 关于」关闭本地词典的语义重排；关闭后仍可普通离线查词。详见[本地数据说明](docs/PRIVACY.md)。

## 数据与更新

阅读、书写、查词和参考翻译离线完成；仅主动检查更新、下载 APK 或提交反馈时使用网络。卸载或清除应用数据前请先备份。

应用内更新会看到当前正式版（versionCode 29）。

## 开发

原生 Kotlin / Android Views，系统 PdfRenderer、PDFBox 与本地 SQLite。需要 JDK 17 / 21、Android SDK 36、Build Tools 36.0.0。

```sh
git clone https://github.com/dpan538/Annota.git
cd Annota/android
./gradlew :app:assembleDebug
```

[Android 工程与签名](android/README.md) · [测试与验收](docs/testing.md) · [离线词库构建](design/dictionary/README.md) · [反馈问题](https://github.com/dpan538/Annota/issues)

## 许可

代码：[MIT](LICENSE)。原创视觉与指南：[CC BY-NC 4.0](LICENSE-VISUALS.md)。字体、图标、词库及示例论文遵循各自许可，见[第三方说明](THIRD_PARTY_NOTICES.md)。

独立开发，与 Xiaomi 没有隶属或官方合作关系。
