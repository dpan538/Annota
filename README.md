# Annota

Annota is a note app specifically designed for the Xiaomi Pad 9 Pro Max.

**纯本地，极简阅读，顺手记。**

Annota 围绕 PDF 阅读和手写笔记设计。没有账号、云空间或跨端同步；文件、笔迹、阅读进度和设置留在设备上。日常阅读、书写与查词不联网，只有用户主动获取更新或提交反馈时使用应用网络功能。

> 当前是 **Android 原生开发测试版**，源码、离线资源与验收记录已入库。已有可构建的 debug APK，尚未发布正式签名 APK。模拟器验证通过不等于小米真机已验收；公网反馈服务尚未部署。

## 设计

- 单行紧凑顶栏，左侧页面导航、中间批注工具、右侧阅读选项分区。
- 单页或相邻两页并排阅读；缩略图侧栏按需打开。
- 笔、铅笔、钢笔、荧光笔、橡皮、直尺、图片、图形和手形工具。
- 离线英汉、汉英查词，支持选字后复制与查词，不承诺整句翻译。
- 快速笔记提供空白、点阵、格子、横线，以及白、灰、黑三种底色。
- 文件长按后收藏、重命名、复制、移动、导出 PDF 或移到废纸篓。
- 松间、晴昼、蓝调、原野四套外观，以及手写笔、手势和关于设置。
- 横版五页使用指南。首次初始化为指南和 Attention Is All You Need 两份真实 PDF，完全离线复制；已删除的文档不会在重启时补回。

已选定唯一初始标识 **3B**：Outfit 600，钴蓝 t，圆角白底，字标在正方形内居中。

<img src="branding/annota-logo.png" alt="Annota 标识" width="240">

矢量母版、尺寸与字体出处见 [品牌资源](branding/README.md)。未采用的候选已从当前项目移出。

## 目前完成到哪里

| 部分 | 已有实现 / 验收边界 |
| --- | --- |
| 本地书库与恢复 | SQLite、原子笔迹快照、50 步历史、文件整理、备份合并恢复；无云备份 |
| 通用 PDF | PdfRenderer 渲染、PDFBox 文字坐标与向量导出，系统导入/保存/分享 |
| 原生读写工具 | 单/双页、缩略图、四种笔、橡皮、图形、图片、贴边直尺；小米真机待测 |
| 离线词典 | 随包 57,841 条英汉、125,301 条汉英；键盘/系统手写 IME，整句翻译不在范围内 |
| 反馈 | 接收服务及 5 项测试通过；线上地址未配置，草稿保留且不伪报成功 |
| 更新 | GitHub 下载、SHA-256 / 签名 / 包名 / 版本校验与系统安装入口；生产签名及真实 Release 尚待完成 |

本次干净构建与 API 36 模拟器 **19 项测试通过**，反馈接收端 **5 项测试通过**；lint 0 errors / 51 warnings。小米笔和系统手写 IME 仍待真机验证。

核对记录见 [Android 实现状态](docs/IMPLEMENTATION-STATUS.md)，剩余工作与优先级见 [流程审计](docs/FLOW-AUDIT.md)。

## 构建 Android 测试包

需要完整 JDK 17/21、Android SDK platform 36 / build-tools 35.0.0。词典和初始 PDF 均已随源码保存，应用运行不下载资源。

```sh
cd android
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
# 仅对没有用户数据的模拟器运行自动化测试：
./gradlew connectedDebugAndroidTest
```

输出为 `android/app/build/outputs/apk/debug/app-debug.apk`。这是开发签名，不能当作正式版；不同机器的 debug 密钥通常不同。两个更新校验测试需要预先放入同签名 APK 夹具；未放入时会跳过。完整构建、测试夹具、签名说明见 [Android 工程](android/README.md)。

第一次打开会得到横版五页《Annota 使用指南》和你可直接批注的 11 页示例论文。论文使用项目所有者提供的原始文件，独立权利说明见 [示例论文说明](android/app/src/main/assets/SAMPLE-PAPER-NOTICE.md)；项目许可不覆盖论文，正式广泛分发前仍需完成权利复核。

## 下载与更新

首个可用版本完成后，签名 APK 会发布在 [GitHub Releases](https://github.com/dpan538/Annota/releases)。请下载 Release 附件中的 APK；GitHub 自动生成的 Source code ZIP 不是安装包。

首次从 GitHub 下载并安装，后续在「设置 → 关于 → 检查更新」查看版本说明，确认后下载 APK，由 Android 系统安装器完成更新。应用不会静默安装。更新使用固定包名与签名，并验证下载文件；覆盖升级需要保留原有本地笔记。

更新分发不需要另建业务后端。匿名反馈需要独立的小型服务，不能把 GitHub Token 放入 APK，也不会把联系方式直接公开到 Issue。详见 [APK 与更新分发](docs/DISTRIBUTION.md)。

## 本地查看设计原型

需要 Node.js 22+、Python 3.11+ 和 Chromium。安装开发依赖属于开发环境准备，不属于应用运行时联网。

```sh
npm ci
npx playwright install chromium
python3 -m pip install -r requirements-dev.txt
```

公开仓库保留可编辑源码。可用随包的 11 页示例生成浏览器阅读测试夹具（页图与预览输出不另行入库）：

```sh
python3 design/render-reader-pdf.py android/app/src/main/assets/attention-is-all-you-need.pdf
python3 design/extract-reader-text.py
python3 design/embed-fonts.py
python3 design/build-reader.py
npm run preview:build
```

打开生成的 `preview/index.html`。当前文字选取测试以原设计使用的 Attention Is All You Need 示例为基准；Android 的通用 PDF 引擎独立实现，不把浏览器测试夹具当作正式引擎。示例来源与分发边界见 [第三方说明](THIRD_PARTY_NOTICES.md)。

本地字体资源为官方原始 MiSans 切片，构建时直接嵌入，不修改字形或重新子集化；软件使用声明与原许可随项目保留。指南手写字体使用 OFL 授权的小赖字体子集。

```sh
npm run test:static
python3 design/dictionary/build.py
python3 design/dictionary/test_lookup.py
# 准备本地示例并构建后：
npm run test:reader
npm run test:guide
npm run test:library
```

原型状态容量受限，图片仅保留当前会话，请勿拿它存储重要笔记。

自动检查的配置模板见 [CI 配置](docs/ci/)。当前上传凭据没有工作流写入权限，模板尚未启用；上述检查均可在本地运行。

## 目录

```text
design/          界面源码、指南、原生事件协议与词典工具
branding/        已选定的唯一标识、字体出处与导出
docs/            流程审计、原生分工、隐私边界和分发方案
scripts/         开发环境检查、预览构建与视觉导出工具
android/         Kotlin 原生工程、离线资产、测试与模拟器验收记录
feedback-service/ 已实现但尚未部署的匿名反馈接收服务
```

## 反馈

功能建议和公开问题可在 [GitHub Issues](https://github.com/dpan538/Annota/issues) 提出。不要把私人文件、联系方式或敏感日志发到公开 Issue。应用内反馈是独立匿名通道；未配置线上地址时只保留草稿，不显示虚假的提交成功。

## 许可

- 原创源代码与工程文档：[MIT](LICENSE)。
- 原创 Logo、插图、指南正文及其图文版式：[CC BY-NC 4.0](LICENSE-VISUALS.md)，署名后仅限非商业使用。
- MiSans、小赖字体、Lucide、ECDICT、CC-CEDICT 及第三方论文：各自许可独立适用，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。

本项目独立开发，与 Xiaomi 没有隶属或官方合作关系。设备名用于说明设计目标。
