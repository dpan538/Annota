# Annota 原生接入预留

当前为 HTML 交互原型。以下事件用于后续宿主适配；不是已实现的 Android 桥接。应用保持本地书库、无账号、无云同步。

## 系统图片选择器

点击阅读顶栏的图片图标，在原型根节点派发可取消事件 `annota:pick-image`：

```js
{
  mediaType: 'image',
  multiple: false,
  page: 1 // 当前操作的 PDF 页，双页模式中可以是右页
}
```

宿主同步监听并调用 `event.preventDefault()`，接管系统 Photo Picker。取消选择不改变页面。未接管时，原型在当前用户点击中直接触发隐藏的 `input[type=file]`，由浏览器显示本地选择器。

选择完成后，在同一根节点派发 `annota:image-selected`：

```js
{
  file: selectedFile, // File 对象
  page: requestedPage
}
```

原型接收 PNG、JPEG、WebP，大小不超过 5 MB，在对应页叠加预览。不上传，不写入源 PDF，不保存图片到预览状态快照。原生版本需要把系统 URI 安全转换为宿主可用资源；生产文件格式和大小限制另行确定。

## 关于 → 检查更新

按钮在根节点派发可取消事件 `annota:check-update`：

```js
{ source: 'about', provider: 'github-releases', repository: null, userInitiated: true }
```

宿主接管时同步调用 `event.preventDefault()`，然后展示真实的检查过程、版本号和更新结果。当前未配置任何版本地址、下载或安装操作。无人接管时提示“已预留更新入口，更新服务尚未接入。”，不声称版本已最新。

仓库由 `project.config.json` 的 `repository` 提供，格式为真实 `owner/repo`，当前为 `null`。后续以 GitHub Releases 分发 APK，用户主动触发才查询版本与下载资产，不嵌入 GitHub Token。下载结束交给 Android 系统安装界面，不静默安装。项目尚未发布，因此本轮不伪造版本或下载链接。参考：[GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)、[Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)。

## 首次初始化与使用指南

`onboarding/bundle/manifest.json` 列出两个固定 ID 的本地资源：`p1` 为 11 页示例 PDF，`guide` 为五页指南笔记。资源附 SHA-256，随安装包携带；第一次初始化原子复制并记录完成标记。启动与应用更新不重复添加，不覆盖用户批注，也不把用户删除的指南补回。当前 HTML 内容源格式为 `annota-guide-html-v2`，页面坐标为 900 × 600（横版 3:2），不是已发布的通用笔记格式；Android PDF / 笔记存储仍须原生实现。旧原型的纵版指南笔迹会按页面尺寸归一化；正式应用的内容迁移不应由这段演示逻辑替代。

`annota:open-reader` 事件接受 `{documentId}`，包括初始文档、新建笔记及文档副本。文档记录的 `sourceId` 指向示例 PDF 或指南内容源，`paperStyle` 为 `blank|dots|grid|lined`，`paperColor` 为 `white|gray|black`。笔记页面使用 612 × 792 坐标，指南为 900 × 600。原型将每个文档的页码、批注、图片和页面顺序独立管理，状态快照包含当前文档及其他文档的压缩批注。图片仅保留当前会话，预览快照仍有限额，不是正式持久化笔迹格式。

指南可发出 `annota:navigate`，内容为 `{screen:"settings",tab:"appearance"|"about",view:"landscape"|"portrait"}` 或 `{screen:"feedback",view}`，使用户直接进入对应入口。

## 文件操作与导出

书库中任意文件长按、右键或键盘菜单键打开操作菜单。收藏、重命名、移动、复制、废纸篓在原型文档状态中生效；原生版需在本地数据库事务中实现，复制文档应复制页面顺序、批注与图片资源引用并分配新文档 ID。

导出为 PDF 派发可取消事件，由宿主同步 `preventDefault()` 接管：

```js
// annota:export-document
{
  documentId: "p1", name: "Attention Is All You Need",
  format: "pdf", includeAnnotations: true, userInitiated: true
}
```

Android 宿主应调用系统保存文件界面（Storage Access Framework），把用户当前页面顺序、旋转及批注合成为 PDF；取消保存不改变文档。原型不接网络导出服务。未挂载宿主时，已知示例 PDF、指南及新笔记生成独立打印草稿，等待嵌入字体与图像完成后调用浏览器系统打印，用户自行选择保存 PDF；导出页数与纸张底色保留。`annota:export-ready` 的 `{documentId,pages,mode:"system-print"}` 仅表示草稿就绪，不是已保存成功。任意导入 PDF 尚未解析，回退明确提示原文件未接入，不生成空白 PDF 冒充导出结果。

快速笔记使用系统内的四种纸纹和三种底色，不下载模板；新建后直接进入笔工具。黑纸默认使用浅色墨迹。更换纸纹仅对手写纸页开放，原始 PDF 页面不覆盖底色。

## GitHub 主页与意见反馈

指南中的 GitHub 项目入口派发可取消事件 `annota:open-external`：

```js
{ url: "https://github.com/owner/repo", source: "guide", userInitiated: true }
```

原生宿主同步接管，打开系统浏览器；原型无人接管时调用新标签页。地址未配置则只显示“项目地址即将公布”，不打开无效链接。

点击提交反馈时，先保留本地草稿，再派发可取消事件 `annota:submit-feedback`：

```js
{
  provider: "in-app", endpoint: null,
  body: "问题与建议", contact: "用户自愿填写的联系方式", userInitiated: true
}
```

`in-app` 由原生宿主在用户点击提交后接管并联网发送正文和选填联系方式。不会自动附带笔记、截图或设备日志，不要求用户登录，也不在客户端内置 GitHub Token。宿主只有收到真实服务成功响应才可清空草稿并提示成功；失败保留草稿并支持重试。`feedbackEndpoint` 当前为 `null`，未接入服务时只提示草稿尚未发送。取消事件只表示请求被宿主接管，不表示已提交成功。GitHub 项目主页与更新渠道独立于反馈服务。

## 仍需原生实现与真机验证

- Android PDF 渲染、原文件与批注的本地持久化。
- Xiaomi 触控笔压感、笔身双击、震动反馈和手掌抑制。
- 设备系统栏 Insets、实际触控范围、双指与三指手势。
- Android 系统图片选择器结果回传及图片资源生命周期。
- 更新检查结果及后续分发方式；当前只预留入口。

## 离线字典

已构建的词库及查询参考实现位于 `dictionary/`。原生宿主读取 APK 附带的 SQLite，不向网络发起查词请求。读操作放入后台线程，所有查询参数绑定；数据库和字体随 APK 安装，不在首次启动时下载。

根节点发出可取消事件：

```js
// annota:dictionary-query
{ requestId: 1, query: "attention", offlineOnly: true, limit: 12 }
```

宿主同步 `preventDefault()`，后台查询后回传：

```js
// annota:dictionary-result
{
  requestId: 1,
  entries: [{ word: "attention", pronunciation: "", meaning: "注意力" }],
  suggestions: [] // 未精确命中时可返回 [{ word: "attention", meaning: "注意力" }]
}
```

`entries` 最多 12 项，`suggestions` 最多 6 项，字符串按纯文本显示；旧请求结果不覆盖新输入。结果只显示词条、音标 / 词性和简明释义，不渲染额外解释段落。查询失败用 `error: true` 和空列表；未收录仅返回空列表。未挂载宿主时预览使用原创论文术语表，不触网。数据库包含 ECDICT 核心英汉和 CC-CEDICT 汉英，具体数量、授权和来源边界见 `dictionary/README.md`。

## 文字选择、复制与翻译

预览从用户提供的 11 页 PDF 提取真实文本和坐标，以透明文字层覆盖原页图。手形工具 / 阅读模式允许浏览器文字选取，书写工具停用文字层命中；缩放、双页、页码重排与旋转沿用对应纸页坐标。选区旁显示“复制 / 翻译”菜单，换页、退出阅读或点击外部会清除选区。扫描 PDF 的 OCR 不在本轮范围内。

“复制”优先交由原生宿主，同步取消事件接管：

```js
// annota:copy-text，可取消；仅由用户点击复制触发
{ requestId: 1, text: "Attention", page: 1 }
// annota:copy-result；写入系统剪贴板后回传，失败必须 success: false
{ requestId: 1, success: true }
```

宿主需实际调用 Android ClipboardManager，不能只消费事件便声称已复制。无人接管时使用浏览器 Clipboard API / 本地复制命令，失败如实提示；请求 ID 防止旧回执覆盖新操作。选中文本仅用于本次操作，不写入预览状态、不上传。

“翻译”把选中的单词 / 短语送入同一个离线词典浮窗。选择完整句子但词典没有对应短语时，显示“暂不支持整句翻译”，不会截取一个词冒充句子翻译。查询超过 80 字符时保留完整选中文本用于显示，不交给词典查询；任何情况都不会转为网络请求。若后续需要完整离线句子翻译，需另行打包本地中英翻译模型并验证大小、性能、授权与质量。

## 字典的键盘 / 手写输入

```js
// annota:request-text-input，可取消；宿主同步接管系统 IME
{ field: "dictionary", mode: "handwriting", text: "" } // 或 mode: "keyboard"
// annota:text-input-result
{ field: "dictionary", text: "attention" }
```

输入框仍是真实可编辑控件。键盘 / 手写入口只显示图标，保留可访问名称、悬停提示和选中态。手写选项请求系统输入法，没有自制拍照上传、云 OCR 或假识别结果。Android 14+ 标准 EditText / WebView 文本框支持系统手写，实际依赖兼容输入法；字典浮窗打开时输入框事件不能穿透至 PDF 画布。必须用目标设备的离线手写输入法验收。原型浏览器可键盘查词，无法证明小米设备系统手写已适配。

参考：[Android 文本框手写](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields)。

## 小米手写笔统一适配层

先依据 Android `MotionEvent` 的工具类型和实测能力处理输入，不由型号名称推断能力。未知型号仍支持系统提供的标准笔事件。压力统一归一化为 0–1，合并历史采样；`ACTION_CANCEL` / 被取消笔画不提交，识别触控笔后抑制同时出现的掌触。当前浏览器原型已经实现 PointerEvent 压力、合并采样、笔 / 触摸分离、取消回滚和双 / 三指轻点，测试为合成事件，并非硬件验证。

| 适配档案 | 已核对的官方硬件信息 | 接入原则 |
| --- | --- | --- |
| Xiaomi Smart Pen 第一代 | 4096 级压感、双按钮 | 标准笔输入；只消费系统确实交付给应用的按键 |
| Xiaomi Smart Pen 第二代 | 4096 级压感、双按钮 | 同上，不假设支持轻点笔身或震动 |
| Xiaomi Focus Pen | 8192 级压感、主副按钮及焦点按钮 | 系统快捷功能保持系统行为，应用只接可用事件 |
| Xiaomi Focus Pen Pro | 16384 级压感、轻捏 / 双按 / 滑动 / 悬停 / 震动等 | 通过系统 / 官方接口探测，按能力启用；优先在用户这支笔上实测 |
| REDMI Smart Pen | 4096 级压感、双按钮 | 同一标准输入路径和能力检查 |

型号覆盖不意味着所有笔可以与所有平板配对；硬件兼容由小米决定。没有设备连接时不显示“已连接”或“已完整适配”。

官方资料：[第一代](https://www.mi.com/my/product/xiaomi-smart-pen/)、[第二代](https://www.mi.com/uk/product/xiaomi-smart-pen-2nd-generation/specs/)、[Focus Pen](https://www.mi.com/global/product/xiaomi-focus-pen/)、[Focus Pen Pro](https://www.mi.com/sg/product/xiaomi-focus-pen-pro/)、[REDMI Smart Pen](https://www.mi.com/global/product/redmi-smart-pen/)。

宿主探测后回传布尔能力，不传蓝牙标识等身份数据：

```js
// annota:stylus-capabilities
{
  model: "focus-pen-pro", // smart-pen / smart-pen-2 / focus-pen / redmi-smart-pen / unknown
  doubleTap: true, barrelButton: false, haptics: false, handwriting: true
}
// annota:stylus-action
{ action: "double-tap" }
```

动作：`double-tap` 使用设置里的画笔 / 橡皮、上一个工具或无操作；`toggle-eraser`、`previous-brush`、`palette`、`brush-settings` 对应官方动作；`slide-up` / `slide-down` 改变笔宽；`barrel-down` / `barrel-up` 临时橡皮并恢复先前工具。原生适配器必须只发出确实可用的事件。原型额外拒绝未宣布能力的 `double-tap` / barrel 动作；其余抽象动作可来自官方回调。

仅在设置开启且宿主宣布 haptics 可用时派发 `annota:stylus-haptic`，内容 `{effect:"tool-switch",model}`。宿主应确认有实际笔身震动控制能力后才宣布；不能用平板振动冒充笔身震动。

小米官方 `MiuiTouchFilmUtils` 提供 Focus Pen Pro 动作回调，应用应遵循初始化、按键派发和生命周期释放要求。触控笔 SDK 中预测 / 识别等部分能力涉及授权与地区条件；**本地首期不接在线授权或云识别链路**。当前仓库没有 SDK 二进制、应用签名或 Android Activity，以上为接入协议，不是已经编译过的 Android 适配器。

参考：[小米 SDK 能力要求](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1882)、[小米客户端接入说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1884)、[Android 高级手写笔输入](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)。

## 网络边界

应用只有用户主动检查 / 下载更新，或在应用内提交反馈时使用网络。阅读、查词、图片选择、手写、缩略图、设置和启动不访问网络；没有账号、遥测、远程字体或云同步。GitHub 项目主页由用户在系统浏览器主动打开，外部页面会联网。手写输入法属于系统组件，应独立确认其离线行为。更新 / 反馈未配置时如实报未接入，不模拟成功。
