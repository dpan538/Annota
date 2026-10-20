# Annota 首次使用内容

首次初始化只预置两个文件：

1. **Attention Is All You Need** — 用户提供的 11 页 PDF 示例，保留原文。
2. **Annota 使用指南** — 5 页可批注的指南笔记，内容源是 `../guide-content.fragment.html`。

指南采用 900 × 600 的横版 3:2 页面，顺序为开始使用与文件整理 → 阅读与查词 → 书写与批注 → 设置 → 本地、更新与反馈。标题与手写提示使用小赖字体（Xiaolai）的嵌入子集，正文使用 MiSans。颜色全部来自项目已确认的四套主题：纸面白色、蓝调蓝、晴昼明黄，以及既有浅蓝、薄荷和淡蓝底色，不另配新色。

页面包含真实论文缩图、阅读界面示意、四种笔迹与试写区；笔种卡可选择实际工具。指南内能进入示例论文、外观、关于更新和意见反馈。每页最多一个实心主按钮，更新和 GitHub 使用文字入口。PDF 与指南的页码、批注和图片会话各自独立。`guide-copy.txt` 导出五页完整文案，供单独审阅或润色。

运行 `python3 design/build-guide-font.py`、`python3 design/build-reader.py`、`python3 design/build-onboarding.py` 生成内容及 `bundle/`。这里提供真实 PDF、指南 HTML 内容源、字体 OFL 许可、页数与 SHA-256 清单，供后续 Android 安装包携带。`annota-guide-html-v2` 是当前横版内容源约定，不代表已经发布或定稿的通用 `.annota` 文件格式。当前交付仍是浏览器交互原型，不是 APK。

手写字体来自 [lxgw/kose-font](https://github.com/lxgw/kose-font) 的 Xiaolai v3.126，以 SIL OFL 1.1 发布。构建源与许可保存在 `../assets/guide-hand/`，嵌入子集内部字体名为 AnnotaGuideHand；资源包携带 `guide-font-OFL.txt`。运行时不请求远程字体。

## 初始化规则

- 两份资源随安装包携带，初始化不下载。
- 在本机数据库事务中检查首次初始化标记；将资源复制到应用私有存储、校验哈希、插入两份文档记录，再提交标记。
- 标记、文件复制及数据库记录需可恢复，失败重试按固定文档 ID 去重。
- 用户打开、批注、重命名或删除后，应用启动和版本更新不覆盖、不重置、不重新添加。
- 升级指南内容时保留用户已写批注的原指南；是否提供新版由后续内容更新流程决定。
- 浏览器原型用 `librarySeedVersion: 2` 表示这轮初始内容。旧版原型的固定占位文件被迁移，用户创建的其他文件保留；此迁移不是原生数据库升级器。

## 本地与 GitHub

`../project.config.json` 的 `repository` 待填真实 `owner/repo`。更新提供方固定为 `github-releases`；用户点击“检查更新”才发出宿主事件。未配置仓库时明确显示尚未接入，不伪装成已是最新版。

日常阅读、批注和查词保持本地；只有主动检查 / 下载更新，以及应用内点击“提交反馈”时允许应用联网。反馈提供方为 `in-app`，正文与选填联系方式通过宿主接口提交，不自动附带文档、截图或日志。`feedbackEndpoint` 尚未配置，当前原型只保留草稿，不显示发送成功。GitHub 作为项目关注入口独立保留；地址未配置时显示“项目地址即将公布”。

未来原生更新流程：读取公开 Releases 的版本和资产信息，展示版本说明，由用户选择下载 APK；校验下载结果后交给 Android 系统安装界面。应用不内置 GitHub Token，不静默安装，不在启动时自动查新。更新分发应使用已发布的 APK 资产，而不是仓库源码 ZIP。

参考：[GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)、[Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)。原生事件约定见 `../NATIVE-INTEGRATION.md`。

## 验证

`ANNOTA_GUIDE_ONLY=1 node design/export-reader-v8.cjs` 导出 `screenshots/onboarding-v1/` 的横竖屏页面与首屏书库，并检查内容不越过页脚、四张试笔卡内文字和曲线不溢出、初始两文件、页面和批注隔离、指南入口、滑动翻页、收藏和状态恢复。

`node design/export-guide.cjs` 从同一内容源导出五页横版 PDF 与五张 2700 × 1800 PNG，保存在项目 `output/pdf/`。PDF 保留可选取文字和嵌入字体，不包含阅读器工具栏；应用内跳转按钮在 PDF 中仅保留外观。导出全程本地进行。
