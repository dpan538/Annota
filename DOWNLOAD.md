# 下载 Annota

稳定版：**1.0.2 正式版**（versionCode 13）。本轮新功能供 **1.0.3-test Beta 测试版**（versionCode 14）先行测试，现通过 GitHub Latest 默认提供给正式版用户的更新检查；包本身仍标明 Beta。两者均支持中文和英文。

## 正式版下载

[**下载 Annota 1.0.2 正式版 APK ↗**](https://github.com/dpan538/Annota/releases/download/v1.0.2/Annota-1.0.2.apk) · [正式版说明](https://github.com/dpan538/Annota/releases/tag/v1.0.2) · [校验文件](https://github.com/dpan538/Annota/releases/download/v1.0.2/SHA256SUMS.txt)

1.0.2 包含手写签名、已签名 / 受限 PDF 兼容性以及此前的阅读、批注和离线词典功能。正式版用户点击检查更新即可发现本轮 Beta，无需先手动安装测试版。

## Beta 测试版下载

[**下载 Annota 1.0.3-test Beta APK ↗**](https://github.com/dpan538/Annota/releases/download/v1.0.3-test/Annota-1.0.3-test.apk) · [测试版说明](https://github.com/dpan538/Annota/releases/tag/v1.0.3-test) · [校验文件](https://github.com/dpan538/Annota/releases/download/v1.0.3-test/SHA256SUMS.txt)

默认更新现提供本轮 Beta，用于测试新笔感、查词能力和完整升级流程。先在旧版「设置 → 导出备份」保存 `.annota.zip`，再下载并覆盖安装；沿用已有证书，不需要卸载。请按[真机测试清单](docs/BETA-1.0.3-TEST-CHECKLIST.md)记录结果。

在平板上点击上述对应版本链接；也可复制 Beta 链接到浏览器：

```text
https://github.com/dpan538/Annota/releases/download/v1.0.3-test/Annota-1.0.3-test.apk
```

下载完成后打开 APK，按 Android 提示允许浏览器或文件管理器安装应用，再完成安装。安装本身不需要注册账号。

Android 按整数 versionCode 判断升级，安装 Beta（14）后，1.0.2（13）不能作为普通覆盖更新装回。不要通过卸载来回退，以免丢失本机数据；后续稳定更新需使用高于 14 的 versionCode。历史版本中，1.0.0 是首个正式版，1.0.1 修正阅读颜色。

## Beta 本轮变化

- 现有笔种加入自适应稳定、压力与速度响应、连续矢量笔形及轻微铅笔颗粒；旧笔迹保持原有外观，不自动重排字形。
- 新增 942 条原创离线短语与术语、682 个显式别名，改善词组语境、跨行断词和 `set up` / `setup` 匹配。
- 可选英汉句子翻译：用户确认后通过 Wi-Fi 下载约 30 MB 模型，完成后可断网处理文本。Google ML Kit 可能联网发送使用与性能指标，输入与译文不上传到 Google；详见[翻译说明](docs/TRANSLATION.md)。
- 修正快速局部擦除穿过稀疏笔画时漏擦、签名画布先掌后笔产生杂线、空白笔记 PDF 导出失败，以及竖长图片插入后的比例失真。
- 「更多 → 手写签名」支持录入、移动、等比缩放、确认、撤销与 PDF 导出。受限 PDF 可只读打开，原件保留；修改已签名 PDF 后另存副本会提示数字签名不保留。
- 使用非调试 Release 构建，沿用原有证书，应用与仓库明确标为 Beta 测试版；构建类型不表示稳定发布。
- 支持高清 PDF、手写批注、只读阅读、离线查词、文内搜索、目录、书签、跳页与按页批注预览。阅读顶栏中央留空，选区保留两项文字操作。
- 从其他应用“打开 / 分享”PDF 到 Annota，加入可调整大小的“继续阅读”安卓小组件。
- 反馈点击提交后自动上传并提示结果；GitHub 创建真实 Issue 后提示“已收到反馈”，失败保留草稿。已通过实际 Android 包、Vercel 与 GitHub 的端到端验证。
- 软件内更新入口：「设置 → 关于 → 应用更新 → 检查更新」。正式版用户默认发现本轮 Beta；安装 Beta 后可发现 versionCode 更高的测试版或正式版，不增加新的通道设置。
- 1.0.3-test 新笔感和句子模型仍需目标设备验证，模拟器结果不代表真实笔尖手感或国行网络表现。证据见[本轮验收](docs/HANDWRITING-LOOKUP-ACCEPTANCE.md)，此前真机测试与[首版验收](docs/INTEGRATION-ACCEPTANCE.md)仅作历史记录。微信、QQ 与厂商桌面的入口表现取决于各自实现。指南正文当前为中文。
