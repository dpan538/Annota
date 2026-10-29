# 1.0.2 正式版验收

- 安装包：`android/dist/Annota-1.0.2.apk`，版本 1.0.2 / code 13，非调试 Release。
- 安装证书 SHA-256 与公开的 1.0.1 一致。包身份与公开校验记录见 release.json。
- `tests-api36.txt`：23 项通过。覆盖手写签名导出重开、数字签名原件字节一致、可见签名外观保留、受限/加密 PDF、旧书库迁移及原有输入/导出行为。
- `touch-ui-api36.txt`：独立虚拟显示器上的录入、拖动、等比缩放与确认。
- 两张 PNG 是原生 Android 界面的实际布局截图，测试线条是合成输入，不是真实用户签名或手写笔硬件验收。
- 测试包仅新装在没有 Annota 数据的模拟器中；使用独立虚拟显示器，不展示或清理其他应用。
- [GitHub Release](https://github.com/dpan538/Annota/releases/tag/v1.0.2) 分发签名包与更新清单。目标小米平板与实际待签文件仍需复核。
- 已匿名完整下载 APK、SHA-256 文件和更新清单，均与上传原件一致；稳定版 `releases/latest` 已指向 `v1.0.2`。发布源提交为 `4bd3c663fe1df6d999264f5003930a003befc4f7`，完整回执见 `release.json`。
