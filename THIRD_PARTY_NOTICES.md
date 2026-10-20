# 第三方资源与署名

根目录 MIT 只覆盖维护者原创代码与工程文档。下列内容不因放在同一仓库而变为 MIT 或 CC BY-NC。

| 资源 | 使用与许可边界 |
| --- | --- |
| MiSans | Xiaomi Inc. 字体。用于界面；官方允许作品与应用嵌入，软件须声明使用。保留 `design/assets/misans/MiSans-License.pdf` 与官方来源清单。公开构建只嵌入官方原始字节，不发布本地实验的修改子集。 |
| Xiaolai / 小赖字体 | LXGW 与 Nozomi Seto，SIL OFL 1.1。指南的嵌入子集内部名为 AnnotaGuideHand；字体许可与来源保存在 `design/assets/guide-hand/`。 |
| Outfit | The Outfit Project Authors，SIL OFL 1.1。已选定字标使用 600 字重并转为轮廓；原许可与固定版本、源文件哈希见 `branding/fonts/outfit-OFL.txt` 和 `sources.json`。 |
| PDFBox-Android / Apache PDFBox | Apache 2.0。原生提取文字与向量导出；完整 Apache 文本随 APK 保存在 `android/app/src/main/assets/APACHE-2.0.txt`。 |
| Bouncy Castle | 原生 PDF 加密支持；保留 `android/app/src/main/assets/BOUNCY-CASTLE-LICENSE.html`。 |
| AndroidX / Android apksig | Apache 2.0；原生系统集成、APK 密码学签名校验。版本由 Gradle 固定。 |
| Gunicorn | 反馈服务运行器，MIT；仅服务端依赖，不随 APK 运行。依赖版本固定在 `feedback-service/requirements.txt`。 |
| Lucide / Feather | Lucide contributors 的 ISC，以及其保留的 Feather MIT 声明。使用于界面功能图标；原文见 `licenses/Lucide-LICENSE`。不是 Annota 原创 Logo 的来源。 |
| ECDICT 核心子集 | 上游 skywind3000/ECDICT 标注 MIT；保留原许可、源数据哈希及筛选方法。公开 APK 发布前复核上游外部数据来源范围，不承诺全部专业词汇。 |
| CC-CEDICT | 社区维护、MDBG 发布；当前随来源保存的头部标注 CC BY-SA 4.0。汉英源数据与 SQLite 转换结果沿用该许可，保留署名、版本、转换方法及可重建源。此数据的 SA 条件不把无关代码自动改为同一许可。 |
| Attention Is All You Need | Ashish Vaswani 等，NIPS 2017。用户本地提供的论文用于私有设计测试；公开仓库不分发原 PDF、页图、文字坐标或含其全文的生成预览。并未取得将论文作为 APK 预装内容再分发的独立授权确认。 |

来源：

- MiSans [官方 FAQ](https://hyperos.mi.com/font/en/faq/) 与 `design/assets/misans/sources.json`。
- 小赖字体 [lxgw/kose-font](https://github.com/lxgw/kose-font)，版本与 SHA-256 见 `source.json`。
- 字标候选字体来自 [Google Fonts 官方源文件](https://github.com/google/fonts)，固定提交与每个文件的 SHA-256 见 `branding/fonts/sources.json`。字体原文件不随候选图片打包，重建脚本按清单获取，原 OFL 许可保持独立。
- [Lucide 许可](https://lucide.dev/license)。依赖包随其完整许可安装。
- [ECDICT](https://github.com/skywind3000/ECDICT)，原许可 `design/dictionary/licenses/ECDICT-MIT.txt`。
- [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)，[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)，原始头部与来源见 `design/dictionary/`。
- 论文 [NeurIPS 出版页面](https://proceedings.neurips.cc/paper_files/paper/2017/hash/3f5ee243547dee91fbd053c1c4a845aa-Abstract.html)、[Google Research 页面](https://research.google/pubs/attention-is-all-you-need/)。公开访问不等于获准作为本产品的预装资源分发。

MiSans 官方 FAQ 允许软件嵌入，并要求注明使用；本地实验字体不作为公开发行依据。Android 正式版需要在「关于 → 开源与资源许可」离线显示实际随包资源的完整署名和许可。

用户自行导入的文档和笔记始终属于用户，不进入项目代码或视觉许可。没有把商业软件截图、参考图或用户个人资料提交到公开仓库。
