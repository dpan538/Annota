# Changelog

## 1.1.1 正式版 — 待发布

- 内置 Wiktionary 普通话译义、LemmInflect 词形与离线英汉短句参考翻译；增强词组分段和多义项排序，所有数据随 APK 分发。详见 [待发布说明](docs/1.1.1-RELEASE-NOTES.md)。

versionCode **21**。本轮仅准备修复与测试，尚未发布 Release / APK，默认更新入口仍为 1.1.0。

- 修复取词后的复制 / 查词菜单在点击空白处、滑动或跳页后仍停留的问题；取消选区同步关闭菜单，保留拖动选区手柄和查词操作。
- 修复阅读顶部栏隐藏后保留空白占位的问题；工具栏向上收起并释放空间，轻点页面恢复。阅读模式闲置约 5 秒自动收起，打开菜单、目录或选词期间暂停；批注模式不自动隐藏。
- [修复与验证记录](docs/1.1.1-RELEASE-NOTES.md)。

## 1.1.0 正式版 — 本地检索与阅读交互

versionCode **20**，沿用原签名，默认更新入口切换至正式版。

- 文件打开 / 返回延长至 460 ms，结合短距离位移、轻微缩放与分阶段交接，避免新旧正文叠显；尊重系统关闭动画设置。
- 更多、图形、字典及画笔浮层支持再次点击同一入口收起。
- 词形精确命中后继续关联原词，完整保留可展开短义项；新增随 APK 提供的 WordNet / Chinese Open WordNet 独立词义索引与不规则词形，按局部证据保守排序。
- 单词查词优先，词组保留分段释义；不引入在线翻译、模型下载或运行时词库下载。
- [发布与验证](docs/1.1.0-RELEASE-NOTES.md) · [开放词库来源与边界](design/dictionary/OPEN-LEXICON-RESEARCH.md)。

## 1.0.7-preview Beta — 柔和文件过渡与语境查词

versionCode **19**，沿用原签名，默认更新检查提供此 Beta。

- 文件打开 / 返回采用 320 ms 缓动、短距离垂直位移、轻微缩放和辅助透明度变化，取消整屏高速横移；阅读内容保持不透明。
- 保留 IT 等缩写的大小写证据，随包补充经 NIST 术语表核对的 9 个缩写、12 个义项；多义缩写仍展示候选，不盲目覆盖。
- 修正 scalable / measured 的专业与普通语境排序；分段查词传递选区内和选区外上下文，点击片段继续保留语境。
- 修复相邻短词点击被选区手柄热区截获，保留实际拖动调节。
- 右侧四个工具按钮整体内收，README 移除顶部大图，增加不同颜色的功能与设备标签。
- [说明与验证](docs/1.0.7-PREVIEW-NOTES.md) · [真机清单](docs/BETA-1.0.7-PREVIEW-CHECKLIST.md)。

## 1.0.6-preview Beta — 连续布局动画、铅笔对比与离线词库

versionCode **18**，沿用现有证书；默认更新检查提供此 Beta，最近正式版为 1.0.2。

- 目录展开 / 收起改为侧栏位移与阅读区域宽度同步变化，保留 ReaderView，移除整屏截图淡出。
- 打开 PDF 向前滑动，返回书库反向滑动；菜单改为短距离位移，内容保持不透明。支持连续反向操作及系统关闭动画。
- 新铅笔采用更深的 v4 石墨覆盖，保留细纹、压力与叠加效果；旧版笔迹保持原外观，屏幕和 PDF 导出共用绘制逻辑。
- 不再按词频和考试标签筛掉 ECDICT 专业词汇；英汉共 771,180 条，汉英 126,272 条。对未整体收录的组合提供有标识的分段释义，使用源词形与最长片段匹配；未知片段明确保留。所有词库随 APK 提供。
- README 拆分中英文，突出功能、截图、安装和开发入口；测试记录集中到 [测试说明](docs/testing.md)。
- [验证范围](docs/1.0.6-PREVIEW-NOTES.md) · [真机清单](docs/BETA-1.0.6-PREVIEW-CHECKLIST.md)。

## 1.0.5-preview Beta — 笔触、旋转与渲染修复

versionCode **17**，沿用已有证书；默认更新检查提供本 Beta，最近正式版仍为 1.0.2。

- 近似直线停笔约 650 ms 自动拉直，抬笔前可调整终点；取消或换工具不落下意外笔迹。
- 框选新增旋转，覆盖笔迹、图形、图片和签名，并保留保存、撤销 / 重做和 PDF 导出结果。
- 新铅笔采用可透纸的石墨纹理，压力控制浓淡、倾斜扩大阴影笔触，重复涂画加深；旧版笔迹保留原外观。
- 修复侧栏离屏行滚入屏幕后长期留白；缩略图独立使用渲染资源，离屏队列可取消，换页不重建整个侧栏；正文可见页底图先于高清分块补齐。
- 移除系统列表选择背景导致的橙色矩形；工具状态改为圆角轻反馈。文档打开 / 返回、侧栏与浮层使用短过渡，并尊重系统关闭动画设置。
- 保持随 APK 交付的纯离线词典，无模型下载、联网翻译或新增设置项。
- 验证范围见[验收说明](docs/1.0.5-PREVIEW-NOTES.md)，目标设备按[测试清单](docs/BETA-1.0.5-PREVIEW-CHECKLIST.md)复核。

## 1.0.4-preview Beta — 离线词典与批注交互

versionCode **16**，沿用已有签名。此次为 Beta 测试版，不作为 1.1.0 正式版发布；默认更新检查提供本轮 Beta，最近正式版仍是 1.0.2。

- 移除 Google 翻译 SDK、模型下载及相关提示、标识；词库随 APK 安装和更新，首次断网即可使用。暂不提供整句翻译。
- 新增批注框选，支持笔迹、荧光笔、形状、图片、签名的移动与等比缩放，以及单步撤销 / 重做。
- 降低直尺旋转灵敏度，增加水平吸附和脱离阈值；改善选词与手柄命中范围，修正荧光笔图标。
- 复用 PDF 渲染器与有上限的缓存；修正缩略图首个视图退出后的刷新遗漏。
- 测试范围与真机重点见[验收说明](docs/1.0.4-PREVIEW-NOTES.md)和[测试清单](docs/BETA-1.0.4-PREVIEW-CHECKLIST.md)。

## 1.0.3-test Beta — 笔触与词组测试版 / Ink and phrase lookup Beta

本轮为 Beta 测试版，versionCode 14；最近正式版为 1.0.2（13）。按维护者要求，当时默认更新入口 GitHub Latest 提供该 Beta，正式版用户检查更新即可发现，应用和发布标题保留 Beta 标识。新功能的真机验证按[测试清单](docs/BETA-1.0.3-TEST-CHECKLIST.md)进行。

- 优化现有笔种：速度自适应去抖、压力过渡与速度响应、连续变宽矢量轮廓；铅笔加入固定在笔迹坐标中的轻微颗粒。屏幕与 PDF 导出共用几何，旧笔迹保持旧版绘制，不自动改字或重新排版。
- 局部橡皮按完整扫过路径切割线段，修复采样点稀疏时中段擦不掉；修正签名板掌触先落时的杂线、单点导出外观和竖长图片等比放置。
- 修复空白笔记没有原始 PDF 文件时触发路径校验、导致 PDF 导出失败的问题；有原件的文档仍禁止覆盖原始文件。
- 随包新增 942 条原创词组 / 学术术语与 682 个词形 / 拼写别名，覆盖常用搭配、论文表达、Attention、机器学习、数学统计、软件及科学工程。新增源文件与许可可审阅、可离线重建。
- 保留词边界区分 `set up` 与 `setup`；支持显式词形、Unicode 连字符与 PDF 断行修复。局部语境优先采用最长且不冲突的完整词组，默认给一条短释义。
- 可选英汉句子翻译沿用查词侧栏；原文不静默截断，最多 4,000 字符。首次由用户确认通过 Wi-Fi 下载约 30 MB 模型，完成后端侧翻译。Google ML Kit 的模型联网及使用 / 性能指标行为单独披露；输入和译文不上传到 Google。
- 非调试 Release 构建作为 Beta 分发，保留既有签名与覆盖升级；Release 构建类型不表示稳定版。新笔感和翻译模型的目标设备验证与自动化测试分别记录，不声称模拟器等同于真机验收。
- Same-certificate Beta with refined existing brushes, swept local erasing, 942 authored phrases and 682 explicit aliases, plus opt-in on-device English/Chinese sentence translation after a model download. The last stable version is 1.0.2; GitHub Latest now offers this Beta to existing stable users on update checks.

## 1.0.2 — 手写签名与 PDF 兼容性 / Signatures and PDF compatibility

- 新增「更多 → 手写签名」，独立录入后移动、等比缩放、确认与撤销，签名随 PDF 导出。
- 修复权限检查导致部分已签名及受限 PDF 导入失败；保留原件，受限文档只读打开，必要时使用私有阅读副本。
- 未修改的文档导出保留原始字节；批注副本保留可见表单 / 签名外观，明确提示原证书签名的验证效力不随副本保留。
- 沿用既有证书，versionCode 13；不提供证书签名或信任链验证。详见[签名验收](docs/PDF-SIGNATURE-ACCEPTANCE.md)。

## 1.0.1 — 中性阅读颜色 / Neutral reading colors

- 新文档默认原始 PDF 白底；柔和预设改为中性浅灰，去除页面和外围背景的暖黄偏色，既有选择仍有效。
- 保留浅灰细线与彩色图表区分，显示变换不改原始文档、笔迹与导出。
- README 新增六张同蓝调原生产品截图及用途说明，统一下载、能力、隐私、开发与许可信息。
- Same-certificate stable patch; original-white defaults and neutral Soft display, with a consistent illustrated product README.

## 1.0.0 — 首个正式版 / First stable release

- 首个非调试正式包，沿用现有证书以保留覆盖升级；应用书库、关于页、README 和 GitHub Release 均明确标注正式版。
- 增加外部 PDF 打开 / 分享与私有持久副本、去重、导入 / 导出进度和失败重试。
- 增加可调整大小的“继续阅读”安卓桌面小组件、跳页与按页批注预览；旋转 / 重建保留搜索和阅读位置。
- 书库筛选改为锚定小菜单，常驻图标无装饰底色；菜单 / 侧栏采用短线性局部过渡。
- 反馈表单按平板字号与 60 / 68 / 76 控件比例显示。单击提交经 Vercel 自动创建 GitHub Issue 后提示已收到，网络 / 服务失败保留草稿；无需用户检查提交结果。
- Verified public APK download, actual Android system upgrade from 0.1.9 and automatic GitHub feedback on the non-debuggable build.

## 0.1.9-dev — 文档搜索与导航 / Document search and navigation

- 新增文内搜索、原页匹配高亮和前后结果切换；退出恢复阅读位置与笔型，旋转保留查询。Added in-document search with original-page highlights, Previous/Next, viewport/tool restoration and rotation retention.
- 选区加入首尾拖动手柄与触摸放大镜，支持同页跨行并隔离常规双栏；小菜单收为纯文字「复制｜查词」。Added selection handles and a touch magnifier for same-page multi-line selection, ordinary column isolation and a text-only Copy / Dictionary menu.
- 现有页面侧栏加入 PDF 目录与页书签，文内链接/目录跳转后可返回原处；书签支持复制、备份与恢复。Added embedded PDF outline, page bookmarks, internal-link return navigation and bookmark copy/backup/restore.
- 阅读顶栏中央留空、两侧纯图标，显示预设移入右侧更多；统一相关面板间距、选中反馈和搜索空态。异步搜索取消过期查询并限制为 1,000 条。Reading controls use side icons only, with presets under More; related surfaces share spacing and feedback, and stale searches cancel with a 1,000-result cap.

## 0.1.8-dev — 极简纯阅读 / Minimal reading mode

- 阅读模式独立于手形工具：隐藏批注和页面编辑入口，笔尾、侧键、撤销手势均不能修改文档；「批注」恢复原工具、颜色、粗细与尺子。
- 单击正文显示/隐藏顶栏，长按仍可离线查词；隐藏顶栏保留布局和阅读位置。
- 只提供原始 / 柔和 / 清晰三个显示预设，以横向纸色与 Aa 预览卡展示，选中项描边/勾选；按文档记忆。轻微页色和对比度变化复用已渲染 PDF 分块，不重新打开文档；原始显示可完全还原。
- 显示处理同时影响 PDF 的文字与图片，应用笔迹及导出保持原样；黑底笔记不反转。没有增加亮度、裁边、重排或额外触摸设置。
- 小菜单锚点使用按钮固定布局边界，避免按下缩放引起参数切换时微移；隐藏阅读顶栏后系统状态图标保持清晰。
- One read-only workspace and three reversible display presets; long-press lookup and one-tap return to annotation. VersionCode 9, development preview with the existing debug certificate.

## 0.1.7-dev — 三档按键与阅读体验 / Control sizes and reading polish

- 60 / 68 / 76 三档同时作用于书库、阅读器顶栏、小菜单与新建面板；设置即时更新并保留当前位置，冷启动保留选择。
- 笔型、粗细和颜色在同一居中菜单内切换；笔/橡皮一次点击切换并记住笔型。手写笔与手势偏好改为带选中状态的小菜单。
- 原始 PDF 可见区域高清分块、稳定预览与精确内存预算；扩大尺子，修正笔采样与掌触，减少翻页及外观切换的整屏闪烁。
- 词典浅色卡片、首行对齐、真实键盘避让；局部上下文优先显示短义，其他义项折叠，手动改词清除旧语境。离线规则覆盖有限。
- 继承未发布的本地 0.1.6 修正；versionCode 8，高于本地候选 7。开发签名 Pre-release，非正式生产版。
- Three real control sizes, persistent in-place menus, sharper PDF zoom and concise offline contextual lookup. Xiaomi hardware acceptance remains separate.

## 0.1.5-dev — 连续阅读与测试更新 / Continuous reading and preview updates

- PDF 连续纵向滚动、惯性与边界、中心缩放、双页连续行及阅读位置恢复；保留适宽/整页与顶部操作。
- 新建/插页/模板共用带纸张预览的圆角下拉；按下、选中、菜单与导航使用线性短过渡。
- 测试包允许测试 Release，按整数版本选更新；提供真实更新清单，恢复已下载包并在安装前重新校验。0.1.4 需手动覆盖一次。
- 38 项回归、实际 UI 重开、截图和原始动效录屏归档；旧 316 轮记录保留为原版本历史。

## 0.1.4-dev — 开发测试预览 / Development preview

- README 压缩并加入目录，两张界面图固定并列。
- 英文设置页按真实字形测量页签，缩小英文页签文字/图标和竖屏页脚；修正工具、纸张、画布与失败提示的中文残留。
- 英文语言切换持久化、两种方向/三档尺寸、纸张/批注菜单和反馈草稿纳入真实 UI 回归；补充历史批次与压力极值输入测试。
- 修复主题预览选中边框被裁切，横竖屏逐边检查；关于页改为「专为阅读」。
- 316 轮原始记录、构建身份、CSV 与内存曲线归档到 `log/`。
- 新增可复跑的持续使用与 APK 下载、签名、资源、安装和冷启动校验脚本。

## 0.1.3-dev — 未发布 / Unreleased

- 首页字标统一黑色，正式 Logo 保留原版钴蓝 t；铅笔方案尝试后放弃。
- 文档封面读取真实第一页，横版指南保留方向；阅读器去掉底部页码及主题色带，默认隐藏系统导航区。
- 首页按需读取封面元数据，页面缩略图列表虚拟化；页图、插图和文字缓存按堆预算限制，后台回收并按需重建。
- 初始化规则收紧：只有全新书库获得论文与指南；任何已初始化的书库在删除、重启或升级后均不补充样例。
- 12 种纸张/底色、十万点摘要、缓存回收/重建及真实系统生命周期/导出/升级闭环纳入回归。

## 0.1.2-dev — 本地开发历史 / Local development

- 品牌：唯一 3B Logo，Outfit 600、钴蓝 t、圆角白底，字形边界居中；启动图标与主页由同一源派生。
- 初始化：五页指南 + 原始 11 页 Attention Is All You Need；升级补论文一次，不恢复已删指南；资源校验、事务去重与失败重试。
- 归档独立 Android 窗口的原生代码、离线词库、反馈服务和模拟器证据。
- 更新流程审计、隐私与分发文档，区分开发验证、真机验收和正式发布。
- 干净构建和 API 36 全部 19 项回归通过，反馈接收端 5 项通过；断网首次初始化和两份 PDF 可读已实际核对。

## 0.1.1-dev — 本地开发记录 / Local development

- 原生界面对照已确认的设计稿重新接入四主题、MiSans、Lucide、横竖屏和三分区顶栏。
- API 36 模拟器 16 项测试通过；实际覆盖升级的 29 个私有文件哈希不变。
- 完成本地存储、PDF 渲染/导出、笔迹/页面/文件管理、完整离线词库和备份恢复。
- 反馈接收服务 5 项测试与双 worker 本地回执验证通过，公网尚未部署。
- 实现 GitHub 更新及 APK 密码学签名/哈希/包名/版本校验；未发布正式签名包。

## 初始设计归档 / Initial design archive

- 整理主页、设置、反馈、阅读器及横版五页使用指南；保留松间、晴昼、蓝调、原野。
- 明确 MIT 代码、CC BY-NC 4.0 原创视觉与第三方资源的独立许可。
