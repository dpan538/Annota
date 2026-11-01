# 下一版调研：手写质感、字形修饰与离线翻译

> 历史 Beta 记录：其中模型下载与外部翻译方案已废弃，相关旧源码链接可能不再存在。1.0.4-preview 仅采用随 APK 提供的本地词典，当前规范见[本地词典说明](TRANSLATION.md)。

调研日期：2026-11-01（Australia/Brisbane）。代码基线：`47d3a399a45b9fb7920a197e97d147a45ed14288`，正式版 1.0.2。

这是源码审计与官方资料调研，以下方案尚未实现或经过目标平板验收。本轮产品重点是用户能直接感知的书写质感、阻尼和字形修饰，以及词组/句子查询。整本保存性能调整降为后续事项，不作为本轮主线。

## 1. 建议的更新范围

| 顺序 | 内容 | 用户能看到的变化 | 实施边界 |
| --- | --- | --- | --- |
| 1 | 自适应稳定、连续笔形、压感与起收笔 | 慢写小字更稳，圆弧更顺，轻重笔过渡自然，钩折仍清楚 | 自动轻度处理；保留真实采样，不依靠明显拖尾制造阻尼 |
| 2 | 克制的笔刷质感 | 普通笔干净，钢笔有适量笔锋，铅笔有细微颗粒 | 沿用已有笔种；避免增加材质商城和大量滑块 |
| 3 | 可逆的字迹整理原型 | 保留个人写法，按需改善字形比例、倾斜和排列 | 必须把字形修饰与单纯排齐分开验收；先预览，再确认 |
| 4 | 离线词组与学术术语 | 选中完整搭配就能得到一条简洁释义 | 优先补词库与匹配正确性，不需要新模型 |
| 5 | 英汉整句离线翻译验证 | 同一侧栏显示完整译文 | 独立翻译模型；通过真机可用性和论文句子质量验证后再决定发布 |

## 2. 现有书写为什么还有质感提升空间

已有压感、历史采样、抬笔终点补齐、笔优先的多指处理、取消回滚和标准主侧键临时擦除；不是从零接笔输入。

- [`ReaderView.kt`](../android/app/src/main/java/io/github/dpan538/annota/ReaderView.kt#L1216) 当前逐段 `drawLine`，没有统一的变宽曲线轮廓。相邻点连得上，不代表小字、弧线和笔锋自然。
- [`PdfEngine.strokeWidth`](../android/app/src/main/java/io/github/dpan538/annota/PdfEngine.kt#L443) 主要是压力的线性宽度映射，没有压力去噪和速度/起收笔模型。
- [`Point`](../android/app/src/main/java/io/github/dpan538/annota/Store.kt#L47) 只保存 x/y/pressure；需要速度相关处理时，应补时间信息和可选设备轴信息，旧数据仍能读取。
- [`SignaturePad`](../android/app/src/main/java/io/github/dpan538/annota/SignaturePad.kt#L14) 是另一套固定线宽、折线输入；正文与签名画布可以共用基础采样与绘制模块，但签名不默认做字形重塑。

建议保留原始轨迹、派生笔形与笔刷版本。修饰应可关闭/撤销，旧笔记不因更新悄悄变样，显示、缩放、橡皮和 PDF 导出使用一致的笔迹几何。

## 3. 阻尼感与模拟书写质感

软件可以改善视觉上的稳定和阻尼感，不能改变玻璃与笔尖之间的实际摩擦。持续拖慢轨迹会增加笔尖与墨迹的距离，不作为目标。

建议先比较以下组合，参数由开发侧调好，用户不必调算法：

1. **速度自适应去抖**：慢写时压制小幅高频抖动；快写时减少过滤，保留跟手性。1€ Filter 是可实验的基线，作者论文讨论了随速度调整滤波以平衡抖动和延迟，并非专门的汉字美化算法。[作者项目与论文](https://gery.casiez.net/1euro/)
2. **转折保护的曲线连接**：根据方向变化保留停顿、尖角、短点和钩，平滑较长弧段；避免把“口”的角、字母尖角和数学符号全部磨圆。曲线拟合与去抖同时控制最大位移，不能无限叠加强平滑。
3. **压感过渡与笔锋**：压力轻度去噪后使用非线性宽度映射；短点保持清晰，起笔避免突然鼓包，收笔自然收窄。不能把每一笔都拉出长尖尾；缺少可靠压力/时间数据时采用保守退化。
4. **细微材质**：普通笔以清晰实心为主；钢笔表现适量粗细变化；铅笔可让颗粒和墨色随压力变化。纹理固定在笔迹坐标上，避免每帧随机噪声、缩放闪烁和导出外观不一致。
5. **荧光笔合成**：按整条笔画处理透明度，验证内部相邻小段连接处是否反复叠色；同一笔的接缝与不同笔的有意重叠分别处理。

Android Ink 提供笔刷、几何和渲染模块，可以作为对照实现。调研时稳定版是 **1.0.0**，可编程自定义笔刷接口的公开进展在 **1.1 alpha**；先比较稳定版现成笔刷和当前引擎改良版，不为一个效果直接把正式版绑到 alpha。[Ink 模块](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-modules)、[版本记录](https://developer.android.com/jetpack/androidx/releases/ink)

官方 `StockBrushes.pressurePen` 已明确结合压力和速度，针对触控笔手写优化，可作为笔感对照；公开列表没有稳定的铅笔笔刷，微纹理需自行设计验证，不能把内部实验实现当作稳定能力。[官方笔刷 API](https://developer.android.com/reference/kotlin/androidx/ink/brush/StockBrushes)

触觉方面：小米 Focus Pen Pro 官方列有笔内触觉马达，用于捏压、双击、滑动等手势反馈；这不代表当前用户的笔具备相同硬件，也不证明支持连续纸面摩擦模拟。所查公开接入资料尚未证实第三方能直接控制笔身震动。平板整机震动也不能等同于纸面摩擦；不承诺现阶段可软件实现真实笔尖阻力。[小米笔产品说明](https://www.mi.com/uk/product/xiaomi-focus-pen-pro/)

## 4. 字形修饰要单独做，不能只换个平滑名称

| 层次 | 做什么 | 可行性与风险 |
| --- | --- | --- |
| 笔画修饰 | 去抖、圆润连接、压感、笔锋 | 可在现有轨迹上离线完成，优先落地 |
| 字形轻修饰 | 在可靠分组后，尝试微调单字的倾斜、宽高比例与重心 | 需要字/词分组；仅平滑点坐标做不到，汉字连笔和补写要单独验证 |
| 行列整理 | 调整基线、行倾斜、过大的字距差异 | 不等于字形美化；英文大小写、上下标、公式必须保留语义位置 |
| 个性化字形重绘 | 修正字内笔画关系，同时维持个人风格 | 需要额外模型和数据验证，不作为已具备能力或确定的短期承诺 |

三星公开的 Handwriting help 包含辅助线、字形修正和对齐，且与“转成文字”分开；公开说明没有提供可直接移植到 Annota 的字形算法或汉字效果保证。[Samsung Notes 官方说明](https://www.samsung.com/us/support/answer/ANS10003634/)

Apple 公开说明其手写修饰使用端侧模型保留近似个人风格，可作为产品效果参考，不能作为 Android 可复用接口。[Apple 手写修饰说明](https://support.apple.com/en-my/121259)

建议交互只增加两个轻量入口：现有笔菜单中的“笔迹修饰”开关；选中手写后的“整理字迹”。后者需要先补手写对象选择能力，不能误把当前 PDF 文本选区当作手写选区。预览允许撤回，确认是一次可撤销操作。实时阶段不自动挪动整字；PDF 边注、公式和签名不自动参与整段排版。

文字识别模型只能辅助判断内容或分组，不能把识别结果换成某款字体后宣称“保留本人笔迹”。例如 ML Kit Digital Ink 输出文字候选，模型需要先下载；它不是字形美化器。[Google 数字墨迹识别](https://developers.google.com/ml-kit/vision/digital-ink-recognition)

## 5. 小米专属能力值得验证，但不代替笔形设计

小米已公开 Pencil Engine，并列出企业实名认证接入流程；不能简单认为第三方没有厂商 SDK。[官方能力页](https://dev.mi.com/xiaomihyperos/ability/Pencil-Engine)

客户端指南列出了预测点、一笔成形、文字识别、悬浮预览和笔身触控等能力。预测/成形/识别需鉴权，另有环境检测及刷新率设置；SDK 获取、授权分发和离线鉴权行为仍需核实。一笔成形输出规整图形，文字识别输出字符串，均不是公开的汉字美化接口。[客户端接入指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1884)

建议作为目标平板的条件性实验：检查真实笔型号、事件、系统能力和鉴权结果，再验证悬浮笔刷预览、临时橡皮和预测。一次只启用一套预测，真实输入到达后替换预测尾部；预测点不进入最终保存或 PDF。Android 官方也明确前缓冲适合局部笔迹，不能把整页缩放搬到前缓冲上。[Android 高级触控笔功能](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)

## 6. 词组与整句翻译

### 实际词库检查

本次直接以 SQLite 只读方式检查随包 `en-zh.sqlite`：

- 总计 **57,841** 条英文词条；含空格的词条仅 **6** 条：`account for`、`bring about`、`ice cream`、`in spite of`、`per capita`、`space shuttle`。这不是说全部复合词只有六个，连字符词另计。
- `neural network`、`machine learning`、`self-attention`、`in terms of`、`as well as` 查无对应条目。
- 当前忽略词边界的 compact 匹配使 `set up` 命中 `setup` 的名词释义，属于需要修正的匹配问题。[Dictionary.query](../android/app/src/main/java/io/github/dpan538/annota/Dictionary.kt#L99)
- 当前上下文能力是局部搭配和词义规则，并非句子翻译模型。[DictionaryReading](../android/app/src/main/java/io/github/dpan538/annota/DictionaryReading.kt)
- 长选区会被输入框 `initial.take(80)` 静默截断；扩大长度之前也没有整句翻译能力。[DictionaryPanel](../android/app/src/main/java/io/github/dpan538/annota/DictionaryPanel.kt#L54)

### 建议先交付的词组增强

沿用当前侧栏，补常用搭配、短语动词和学术术语；优先完整词组匹配，再处理经过词边界验证的连字符/断行变体。默认给一条短释义，不把词组拆成多个词义堆给用户。扩充来源应保存许可与构建来源，不能把网页释义直接批量抄入词库。

### 整句翻译可做，但需单独验证

建议先限定英汉短句，复用同一面板：单词/词组显示释义，句子显示一段完整译文。选区菜单仍只有两项文字操作，第二项按内容呈现“查词”或“翻译”；不加独立聊天页、服务商切换器或一串语言选项。

候选基线是 ML Kit 端侧翻译：模型下载后可在设备上运行，官方指出它面向普通、简单翻译，论文术语、长从句和否定关系需独立评估。[能力与限制](https://developers.google.com/ml-kit/language/translation)

官方 Android 文档说语言模型约 30 MB，并提供下载/删除接口；这是模型量级，不是整个功能的确定增包量。需要先在目标国行设备与实际网络下验证模型取得、断网运行、耗时和资源占用，不能把“端侧运行”写成“首次安装断网就能用”。[Android 接入](https://developers.google.com/ml-kit/language/translation/android)

未下载时明确显示“下载离线翻译包”，由用户触发；模型缺失/失败不影响原有离线查词。不自动把选中文字发到远程翻译。当前隐私说明只包含更新和反馈联网，如发布模型下载，需同步更新说明。若采用 ML Kit，还要遵循官方翻译归属标识要求。[使用规范](https://developers.google.com/ml-kit/language/translation/translation-terms)

若该方案在目标设备上不能可靠交付，再评估可合法分发的紧凑英汉模型与独立端侧推理。ONNX Runtime 支持 Android 端侧部署，但它只是运行时，不能替代翻译模型、分词器、许可证和质量验证。[ONNX Runtime Mobile](https://onnxruntime.ai/docs/tutorials/mobile/)

句子必须保留完整原文；不能继续静默截取 80 字符，也不能复用“中文释义最多 20 字”的精简器截掉句意。

## 7. 顺手修正与暂缓项

这些是审计发现的具体问题，不改变本轮以手感为主的顺序：

- 局部橡皮按线段判定命中，但只删除圈内采样点；两端在圈外的长线段中部可能擦不掉。需几何切割并补交点。[ReaderView.erase](../android/app/src/main/java/io/github/dpan538/annota/ReaderView.kt#L1316)
- 签名板在掌触先落、笔后落时未清除先前掌触轨迹，需与正文共享正确的输入接管逻辑。[SignaturePad](../android/app/src/main/java/io/github/dpan538/annota/SignaturePad.kt#L102)
- 单点在屏幕是圆点，PDF 导出是偏移方块；应统一几何。[屏幕绘制](../android/app/src/main/java/io/github/dpan538/annota/ReaderView.kt#L1218)、[PDF 导出](../android/app/src/main/java/io/github/dpan538/annota/PdfEngine.kt#L404)
- 普通插图宽度固定后单独限制高度，会压扁竖长图片；可复用签名的等比放置流程。[图片插入](../android/app/src/main/java/io/github/dpan538/annota/MainActivity.kt#L1846)

同步整本快照、每 tile 重开 PdfRenderer、实时与已完成笔迹重绘分层保留为性能候选。对应路径确实存在，但本次没有目标设备耗时测量；只有影响本轮修饰效果或真机出现明显问题时才提到前面。

## 8. 验收重点

先使用同一批真实采样轨迹回放比较，再在 Xiaomi Pad 9 Pro Max 和实际手写笔上盲比当前版与候选版。样本应包括中文小字、英文连写、数字、钩折、短点、慢斜线、快速曲线和公式，而不只是一条漂亮长线。

- 质感：抖动减少，尖角/钩/短点不消失，压力无突然鼓包，起收笔自然；同一笔画在实时、收笔后、缩放、重开和导出中的外观一致。
- 阻尼：稳定感有提升，快写无明显拖尾；不能为了平滑让落笔与显示脱节。帧时与真实笔尖到墨迹延迟分开记录，不能用模拟器代替手感验收。
- 字形：单字结构与行列整理分开看；保留个人特征，不能漏笔、换字、移动公式上下标。分组不可靠就保留原貌。
- 阅读场景：继续以 *Attention Is All You Need* 为主要演示，使用正文、图示和公式页，验证放大批注；专用极端夹具只用于回归。
- 查词/翻译：覆盖 `set up ≠ setup`、完整词组、跨行断词、双栏隔离、超过 80 字符的选区。译文检查术语、否定、数量和关系，不能只评价“读起来顺”。
- 极简交互：笔刷不增加一排配置；原始笔迹可恢复；模型未准备时有明确状态，基础阅读/书写/查词持续可用。
