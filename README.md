# Annota

**纯本地，极简阅读，顺手记。**

面向小米平板 9 Pro Max 的 PDF 阅读器和手写批注本。没有账号、云空间和跨端同步，文件、笔迹、阅读进度和设置都放在本机。断网也能读、能写、能查词。

[![APK](https://img.shields.io/badge/APK-开发测试版-2f6f4e)](https://github.com/dpan538/Annota/releases)
![license](https://img.shields.io/badge/code-MIT-blue)

<img src="android/verification/reader-polish-library-landscape.png" width="62%" alt="主页，松间主题，横屏">
<img src="android/verification/reader-polish-paper.png" width="36%" alt="阅读器，松间主题，单页阅读">

当前为 `0.1.3-dev` 开发测试版，尚未发布正式签名 APK。日常使用不联网；只有主动检查/下载更新、提交反馈时使用应用网络功能。

## 界面

| 区域 | 内容 |
| --- | --- |
| 主页左侧 | 搜索、主页、收藏，以及设置和意见反馈 |
| 主页上方 | 新建笔记、导入文件、新建文件夹；右上角筛选、废纸篓、中英文切换 |
| 阅读器顶部 | 左侧页面导航、中间批注工具、右侧阅读选项 |

阅读区单页或相邻两页并排，缩略图列表按需打开。书库读取真实首页封面，指南保持横版；阅读画布底部不放页码和操作条。四套外观：松间、晴昼、蓝调、原野。快速笔记有空白、点阵、格子、横线四种纸，白、灰、黑三种底色。文件长按可收藏、重命名、复制、移动、导出 PDF、移进废纸篓。

首次启动预置两个文件：横版五页的使用指南，以及 11 页的 *Attention Is All You Need* 示例论文。两份 PDF 随包离线初始化，删除后不会在重启或升级时补回。

## 顶栏

横屏原型以 3408 × 2272 展示，设计坐标为 1024 × 682⅔。阅读器是一行顶栏，分成三块：

| 区 | 内容 | 图标数 |
| --- | --- | --- |
| 左 | 返回书库、缩略图侧栏、单/双页切换 | 3 |
| 中 | 笔、橡皮、直尺、图片、图形、手形 | 6 |
| 右 | 新增页面、阅读模式、离线词典、更多 | 4 |

四种笔收在笔菜单里，颜色和粗细在同一处选择。主页与设置的按键尺寸有 60 / 68 / 76 三档；阅读器保留紧凑的三分区布局。

## 实现

| 环节 | 做法 |
| --- | --- |
| 墨迹 | 读取标准 MotionEvent 压感与历史采样；当前笔画单独绘制，抬笔提交；撤销保存页序与矢量笔迹的压缩状态，最近 50 步可跨进程恢复，不存整页位图 |
| 词典 | 英汉、汉英各一份带索引的 SQLite 词库随包安装，SHA-256 校验后在本机只读查询，支持词形和拼写候选 |
| 更新 | 计划通过 GitHub Releases 分发；应用内检查、下载并校验 SHA-256、签名、包名和版本，再交系统安装器；正式发布流程尚待验收 |
| 存储 | 文件、笔迹、阅读进度、设置全在本机；同签名覆盖升级有模拟器数据保留记录，支持本地备份与合并恢复 |
| 内存 | 首页只读封面元数据，可见封面按需渲染；页面、图片和文字缓存按设备堆预算限额，后台回收后重建 |

标准笔输入和多指手势已有模拟器回归；小米真机、系统手写输入法仍待验收，专有笔身双击、轻捏和震动尚未启用。遇到问题请带上机型、手写笔型号和固件版本提 issue。

## 数字

以下是目标预算，尚未在目标小米平板上完成性能实测，不代表当前版本已经达到。

| 指标 | 目标预算 |
| --- | --- |
| 冷启动到书库可点 | ≤ 800 ms |
| 落笔到出墨 | ≤ 1 帧 |
| 翻页（相邻页已解码） | ≤ 100 ms |
| 词典查询 | ≤ 20 ms |
| 空载常驻内存 | ≤ 120 MB |
| APK | 单包，不分 ABI |

后续发版在 Release 说明里记录设备、测试条件与实测值。

## 不做

- 云同步、账号、协作。笔记留在本机，不设笔记上传通道。
- 整句翻译。保留离线查词，不引入联网翻译或额外的大模型包。
- OCR。扫描件不进文字层，也不假装能选中。
- 手机版和 iPad 版。首期围绕这块平板设计，其他屏幕与设备不在当前验收范围。
- 接管系统文件关联。PDF 从应用里导入，不让系统把所有 PDF 都交给它。

## 设计原型

`design/` 里是能跑的 HTML 原型，不是静态稿。布局坐标、四套配色、按键尺寸从这里出，Android 侧的 dp 按它换算；横竖屏切换、长按菜单、手势分配先在浏览器里定稿。Playwright 脚本检查布局边界、交互和状态，并导出截图供视觉复核；原生测试另外对照原型测量关键控件的位置与尺寸。

原型嵌的是 MiSans 官方原始分片，构建时直接嵌入，不重新子集化；Android 使用官方原始可变字体。两端共用字体来源，字形渲染和行高仍要在真机复核。

## 仓库

```text
android/           原生工程：阅读器、墨迹、词典接入、更新
design/            界面源码、五页指南、原生事件协议、词典构建
branding/          标识与图标
docs/              架构、流程审计、隐私边界、分发方案
scripts/           环境检查、预览构建、视觉导出
feedback-service/  匿名反馈的协议与实现
```

## 构建

需要完整 JDK 17/21、Android SDK platform 36 和 build-tools 35.0.0。`design/` 的脚本另需 Node.js 22+、Python 3.11+ 与 Chromium。

```sh
git clone https://github.com/dpan538/Annota.git
cd Annota/android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

开发包使用本机 debug 签名。正式发布使用固定的 keystore，通过 `ANNOTA_KEYSTORE`、`ANNOTA_STORE_PASSWORD`、`ANNOTA_KEY_ALIAS`、`ANNOTA_KEY_PASSWORD` 环境变量配置；密钥和密码不入库。详见 [Android 构建与签名](android/README.md)。

仓库已随包保存上述原始示例论文；生成的页图、文字坐标和浏览器预览不另行入库。在仓库根目录准备原型依赖并构建：

```sh
npm ci
npx playwright install chromium
python3 -m pip install -r requirements-dev.txt
python3 design/render-reader-pdf.py android/app/src/main/assets/attention-is-all-you-need.pdf
python3 design/extract-reader-text.py
python3 design/embed-fonts.py
python3 design/build-reader.py
npm run preview:build
```

产物在 `preview/index.html`。浏览器原型用于设计与交互验证，不用于保存重要笔记。

## 测试

| 命令 | 覆盖 |
| --- | --- |
| `cd android && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` | 构建、测试 APK 编译与 Android lint |
| `cd android && ./gradlew :app:connectedDebugAndroidTest` | 专用模拟器上的原生仪器测试 |
| `npm run test:static` | 源码与许可检查 |
| `npm run test:reader` / `test:guide` / `test:library` | 视觉与交互 |
| `python3 design/dictionary/build.py && python3 design/dictionary/test_lookup.py` | 词典构建与查询回归 |
| `cd feedback-service && python3 -m unittest -v` | 反馈接收服务 |

当前记录：API 36 ARM64 模拟器 24 项通过，反馈接收端 5 项通过，lint 0 errors / 54 warnings。仪器测试会重置测试书库，只对无用户数据的专用模拟器运行；两个更新校验用例需要先放入同签名 APK 夹具，未放入时会跳过。浏览器测试需先完成上面的原型构建，自动断言与截图复核共同验收。本轮设计与持久化/内存验收见 [阅读器验收](docs/READER-ACCEPTANCE.md)。记录与剩余工作见 [实现状态](docs/IMPLEMENTATION-STATUS.md) 和 [流程审计](docs/FLOW-AUDIT.md)。

## 反馈

功能建议和公开问题走 [GitHub Issues](https://github.com/dpan538/Annota/issues)，按模板填，别贴私人文件、联系方式或完整日志。

应用内的「意见反馈」是另一条路：无需账号，只发送你输入的正文、选填联系方式和用于重试去重的请求 ID，不自动附带设备标识、笔记、PDF、截图或日志。接收服务已有实现，公网地址尚未配置；未收到真实成功回执就保留本地草稿。细节见 [隐私说明](docs/PRIVACY.md)。

## 许可

- 原创源代码与工程文档：[MIT](LICENSE)
- 原创标识、插图、指南正文及版式：[CC BY-NC 4.0](LICENSE-VISUALS.md)，需署名，仅限非商业
- Outfit、MiSans、小赖字体、Lucide、ECDICT、CC-CEDICT、第三方论文：各自许可，见 [第三方说明](THIRD_PARTY_NOTICES.md)

示例论文使用项目所有者提供的原始文件，不适用本项目的 MIT 或 CC BY-NC；来源及正式分发前的权利复核见 [随包说明](android/app/src/main/assets/SAMPLE-PAPER-NOTICE.md)。

本项目独立开发，与 Xiaomi 没有隶属或官方合作关系，设备名只用于说明设计目标。

## English

Annota is a local-first PDF reader and handwriting notebook for the Xiaomi Pad 9 Pro Max. Reading, inking and dictionary lookup happen on the same page; files, strokes, reading position and settings stay on the device.

No account, no cloud, no sync. The dictionary is an offline ECDICT/CC-CEDICT pack. GitHub Releases and the system installer are the planned distribution route; in-app feedback is opt-in and requires no account.

The current build is a development APK. Xiaomi hardware validation, production signing, a public feedback endpoint and the complete published-update flow are still pending.

MIT for code, CC BY-NC 4.0 for original artwork and the guide.
