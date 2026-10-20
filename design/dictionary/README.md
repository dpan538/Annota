# Annota 离线词典包

词库随安装包发布，查词只读本机 SQLite。应用运行时不下载词库、不请求翻译接口，不上传查询词、手写轨迹或 PDF。当前仓库为交互原型；这里已生成真实数据库及 Python 参考查询器，尚未打成 Android APK，也未接入原生查询宿主。

## 本次已生成

| 方向 | 数据 | 条目数 | 未压缩大小 |
| --- | --- | ---: | ---: |
| 英 → 中 | ECDICT 核心子集，含英文释义、中文释义、音标、已收录词形 | 57,841 | 15,724,544 字节 |
| 中 → 英 | CC-CEDICT，支持简体、繁体、带声调数字的拼音 | 125,301 | 14,618,624 字节 |

两库共约 28.94 MiB（包含模糊查询索引）。数量是词条数，不是互不重复的单词数。英文子集保留有中文释义且带考试标签或 BNC / FRQ 排名不超过 50,000 的条目；没有声称覆盖所有专业术语。中文库为汉英词典，不是汉汉字典；不提供完整中文释义、字形演变或成语典故。未收录时如实返回无结果，绝不自动转联网。

HTML 阅读预览仍内嵌约 30 条原创论文术语，用于展示交互；界面标注“Annota 论文词库 · 离线”。完整数据库通过 `annota:dictionary-query` / `annota:dictionary-result` 接入，见 `../NATIVE-INTEGRATION.md`。预览不把数据库塞入页面，也不会伪装成完整词库已连接。

## 离线构建和查询

```sh
python3 design/dictionary/build.py
python3 design/dictionary/lookup.py attention
python3 design/dictionary/lookup.py 学习
python3 design/dictionary/test_lookup.py
```

构建、查询、测试均不需要网络。`sources/` 保存构建所需原数据，`bundle/manifest.json` 记录版本、来源和 SHA-256。首次准备英文核心子集时可提供本地完整 CSV：`build.py --ecdict /path/to/ecdict.csv`。不得在客户端启动时执行下载。

原生实现：把 `bundle/*.sqlite` 与许可说明置于 APK assets；安装后原子复制至私有存储、校验 manifest、只读打开。查询在后台线程执行，使用参数绑定；英语先精确匹配，再按词库提供的词形查询，未命中给前缀及轻微拼写错误候选。输入做大小写与全半角归一化；短词允许 1 个编辑，较长词允许 2 个编辑，支持相邻字母交换，并用正反向索引限制候选扫描范围。中文同时索引简繁体，支持前缀与包含关键词查询。候选必须由用户点选，不擅自替换原词。不使用全量内存加载，也不依赖 SQLite 扩展。字典只会随用户明确触发的应用更新一并更新。

阅读页“选取 → 翻译”与手工查词复用这个离线查询接口。它提供词典释义，不能把逐词拼接当作整句翻译；完整句子没有匹配短语时明确提示当前能力限制，不调用在线翻译。原型已经补充这份示例 PDF 的真实文字层与复制菜单，通用 PDF 引擎及原生剪贴板仍由后续 Android 宿主提供。

## 来源和署名

- **ECDICT** — Linwei / skywind3000，项目标注 MIT，原许可保存在 `licenses/ECDICT-MIT.txt`。[项目与数据说明](https://github.com/skywind3000/ECDICT)、[许可](https://github.com/skywind3000/ECDICT/blob/master/LICENSE)。本包对源数据作核心词筛选、换行标准化及索引转换，保留署名。上游说明数据来自多种资料；公开发行前仍需复核所选数据的来源链，而不把仓库代码许可当作所有外部材料的单独授权证明。
- **CC-CEDICT** — 社区维护、MDBG 发布，2026-10-19 版本，**CC BY-SA 4.0**。来源：[下载与许可页](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)，[完整许可](https://creativecommons.org/licenses/by-sa/4.0/legalcode)。原始头部保存在 `licenses/CC-CEDICT-header.txt`，其中也记录 Paul Andrew Denisowski 的 CEDICT 贡献。本包仅将其格式转换为 SQLite 并建立索引；`zh-en.sqlite` 及其数据转换结果沿用 CC BY-SA 4.0，原始数据随 `sources/cedict.txt.gz` 提供。CC-CEDICT Wiki 旧页仍写 3.0，本次以下载页和实际文件头明确标注的 4.0 为准。

正式应用在“关于 → 开源与词库许可”本地展示以上署名和许可，不要求打开网页才能阅读。

## 手写和联网边界

键盘和手写向同一个查词输入框提交文字。原生版本使用设备已安装、已验证可断网工作的手写输入法；Android 14+ 的标准文本框可接入系统手写。系统输入法有自己的网络行为，不能仅凭 Annota 不发请求便断言输入法也不联网，需在目标小米设备上用飞行模式验收。

首期不接需要在线鉴权的手写识别服务，也不在字典中请求在线发音或整句翻译。应用网络模块仅用于用户主动检查 / 下载更新，以及在应用内提交反馈。没有自动遥测、远程字体、分析 SDK、登录或云同步；查词内容不上传，反馈不自动附带文档、截图或日志。反馈服务尚未配置时仅保留本地草稿。
