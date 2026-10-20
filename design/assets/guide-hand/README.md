# 指南手写字体

当前指南使用的小赖字体子集已保存在 `annota-guide-hand.woff2` 与 `font.css` 中，SIL OFL 1.1 与原始署名见 `OFL.txt`。内部字体名改为 AnnotaGuideHand，保留原版权信息。

完整的上游 TTF 不提交仓库。需要重新生成子集时，从 `source.json` 指定的官方 release 获取原文件，核对其中 SHA-256，存为本目录 `Xiaolai-Regular.ttf`，再运行 `python3 design/build-guide-font.py`。这属于开发构建步骤，不在应用启动时下载字体。

字体软件保持 OFL；使用字体创作的 Annota 原创 Logo 与指南视觉资产适用项目的 CC BY-NC 4.0，并不重新授权字体软件。
