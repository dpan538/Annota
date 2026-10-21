# Changelog

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
