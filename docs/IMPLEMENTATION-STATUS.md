# Android 实现与验收状态

2026-10-20（Australia/Brisbane）。用户已选择先交付 **debug 测试包与真机验收步骤**。
本文记录独立 Android 开发窗口的实际交付和验收边界。随后设计窗口负责源码归档、最终品牌与初始化资源整合。尚未发布 Release。

## 当前修正（0.1.3-dev / versionCode 4，2026-10-21）

- 正式原版 Logo 保留钴蓝 t，首页同字形全黑；已放弃铅笔替换尝试。
- 真实首页封面、横版指南；阅读器底部无页码或色带，导航手势区默认隐藏。
- 首页流式读取封面元数据；可见封面和页面列表按需渲染。位图/图片/文字缓存按正常堆预算限额，后台清空、前台重建；测试验证不影响已保存笔迹。
- 任何历史初始化完成后都不再补任何样例，包含旧的仅指南开发版；以下 0.1.2 的“升级补论文”规则仅为历史，已被本条替代。
- 24 项原生回归覆盖全部 12 种纸张、十万点轻量书库、缓存回收重建、底部与导航区、首次初始化/删除/升级等；当前 lint 0 errors / 54 warnings。
- 真实 UI、新建/书写、系统导出、进程/模拟器重启与实际同签名覆盖升级的完整结果及可复跑方式见 [阅读器验收](READER-ACCEPTANCE.md) 和 `android/verification/reader-polish-*`。

## 历史整合（0.1.2-dev / versionCode 3）


- 选定唯一 3B Logo：Outfit 600，钴蓝 t；圆角白底字形居中。Android 启动图标、主页字标与浏览器原型使用同一母版。
- 首次书库改为五页指南 + 用户提供的原始 11 页 Attention Is All You Need。全部随包离线校验；旧版仅补论文一次，删除的指南/论文不复活；复制失败不发布半套文档。
- 干净构建 `assembleDebug` / `assembleDebugAndroidTest` / `lintDebug` 成功，lint 0 errors / 51 warnings；其中一项新增提醒是 minSdk 29 下保留兼容图标目录 v26，其余为既有原生实现建议。
- 全部 **19 项 Android 回归通过、无跳过**（原 16 项 + 初始化/删除、旧版迁移、复制失败重试 3 项），包括预先放入的同开发签名 versionCode 4 更新夹具。反馈服务 5 项也重新通过。
- 实际 `install -r` 由 0.1.1-dev (2) 到 0.1.2-dev (3)，首次新版启动前 8 个既有模拟器文件哈希全部不变；这与原窗口的 29 文件样本不同。迁移后的已有笔迹保留由新增回归覆盖。
- 飞行模式首次启动实际显示两份文档，论文可打开，横竖屏书库截图已检查。结果记在 `android/verification/integration-*`；不是小米真机测试。
- 论文来源和权利边界、Outfit OFL 已接入离线许可页；公开广泛分发前仍需论文权利复核。
- 源码归档、Logo 和初始化分为三个独立提交，每个保留中英文标题与正文。无 Release、无生产签名、无公网反馈上线。

## 独立 Android 窗口的 0.1.1-dev 记录

以下构建数字与截图描述上一开发版，不覆盖上述初始化与品牌修改。保留纠偏过程和实际验证记录，方便区分已验证的版本。

## 界面纠偏记录

用户指出最初测试界面与确认稿差异过大，此反馈属实。`0.1.0-dev` 的 MainActivity 重新搭建了简化原生布局，只能作为功能验证，不能算设计还原通过。目录一直正确，偏差来自实现方式。

修正版以 `Annota/design/home-ui.fragment.html` 为主页/设置/反馈依据，以 `reader-v2.fragment.html` 为阅读器依据：原稿尺寸、四套颜色、MiSans 400/500、同版 Lucide 图标、侧栏选中态、LIBRARY 标题、创建卡副标题、缩略图与标题/时间分离、完整四分类设置页均重新接入原生控件。设计源只读，文件哈希记录在 `android/verification/design-source.json`。 修正版实际截图以 `verification/design-*.png` 为准；用户参考图的蓝调配色可在“设置 → 外观 → 蓝调”切换。首次书库仅有使用指南，设计样例文件夹和四份文档没有作为假数据写入。

## 交付范围

已建立原生 Kotlin Android 工程，固定包名 `io.github.dpan538.annota`，min API 29，target / compile API 36，修正版 `0.1.1-dev` / `versionCode=2`。

- 本地书库：真实 PDF 经系统文件选择器导入；文件夹、收藏、重命名、复制、移动、废纸篓与恢复；最近打开时间、当前页、页序、旋转与批注保存。源 PDF / 图片在应用私有目录，SQLite 事务引用先原子落盘的压缩笔迹快照；最近 50 步撤销/重做可跨进程恢复。快照使用内容哈希文件，避免大 JSON 超过 SQLite CursorWindow；定期回收未引用快照。
- 备份：系统保存 `.annota.zip`，包含当前全部文档、页序、旋转、笔迹、图片、分类和哈希。恢复先校验，再分配新 ID 合并；不覆盖已有文档。备份不含撤销历史、外观偏好和反馈草稿。卸载/清除数据会删除私有数据；应用云备份和设备迁移均关闭。
- PDF：Android PdfRenderer 原生通用渲染与缩略图、缩放/适宽、相邻双页、文字选取/系统剪贴板/离线查词。PDFBox-Android 提取真实坐标，导出保留源 PDF 向量页面和文字，再叠加笔迹/图片；纸张背景本地生成。页面可新增、复制、旋转、删除、移动，清除批注可撤销。SAF 保存及 Android 分享入口已实现。
- 输入工具：笔、铅笔、钢笔、荧光笔、整笔/局部橡皮、仅擦荧光笔、直线/矩形/椭圆/箭头/三角形与填充、系统 Photo Picker、可拖动和双指旋转的贴边直尺。标准 MotionEvent 压力、历史采样、系统取消、笔/触摸分离及标准笔按键；双指/三指轻点动作可配置。
- 离线词典：57,841 条英汉与 125,301 条汉英随 APK 安装，校验 SHA-256 后只读打开；绑定参数、后台查询、词形、前缀/包含与拼写候选，过期请求隔离。源数据、署名与完整 CC BY-SA 4.0 等许可离线附带。标准 EditText 请求系统键盘或手写输入法。没有汉汉释义、OCR 或整句翻译承诺。
- 反馈：应用保留正文/选填联系方式草稿；只有收到 `accepted=true` 和非空真实回执才清空。未配置地址/失败不报成功。`feedback-service/` 是可部署的匿名 HTTPS 接收层（Gunicorn + Caddy 配置），含校验、幂等、SQLite 落盘、私有限流标识、保留期限、无公开读取接口；不发送文档/截图/日志，不含 GitHub Token。
- 更新：只在用户点击时查询 `dpan538/Annota` 最新正式 Release、显示说明并等待下载；Release 必须含真实 APK 和 `annota-update.json`。HTTPS 域名限制、下载大小/取消/失败处理、SHA-256、apksig 密码学签名、包名、准确 versionCode/versionName 和当前安装证书核对后才交系统安装。安装来源权限可重试。生成真实更新清单的脚本位于 `android/scripts/make-update-manifest.py`。
- 界面：保留四套配色、横屏四列/竖屏两列书库和三分区 **13 个紧凑顶栏图标**。指南采用设计窗口已经导出的原版横版五页 PDF，可批注；首次初始化一次，删除后不补回。

## 已实际完成的验证

### Android

本地 ARM64 Android 16 / API 36 模拟器，density=240；横屏 1800×1200、竖屏 1200×1800。它不代表小米真机。

- `assembleDebug`、`assembleDebugAndroidTest` 和 `lintDebug` 成功；lint **0 errors / 50 warnings**。保留的警告主要为 KTX 建议、绘制时分配、自定义 View 构造、显式左右方向、草稿同步落盘、依赖新版本提示及第三方库未被应用网络模块使用的 TLS 辅助类。应用没有自定义信任所有证书的 HTTPS 配置。
- **16 项测试全部通过（14 项功能/输入 + 2 项设计源码布局）**：任意生成 PDF 的渲染/文字、加密/损坏/取消、向量文字保留、原始旋转叠加用户旋转后的笔迹与图片实际像素位置、持久化/撤销/复制/废纸篓/指南不补回、96,000 点大快照、完整词典与候选/注入输入、备份恢复/危险路径拒绝、原子失败保留旧值、模拟笔压力/掌触/取消/橡皮回滚、真实签名 APK 校验与篡改拒绝。
- 真实 `adb install -r` 从原 `0.1.0-dev (1)` 覆盖到修正版 `0.1.1-dev (2)`，**29 个私有库/数据库文件的 SHA-256 全部不变**，见 `design-upgrade-data-retention.json`。早期独立升级夹具另保留 51 文件不变的历史记录，均未发布。
- 已通过真实系统 SAF 选择并导入生成的 3 页 PDF，书库出现实际文件，原生阅读器可打开。横竖屏顶栏和 Android 16 返回行为已检查。真实 EditText 输入 `attention` 返回随包词库释义。未配置反馈服务时已实际显示失败，并在强制停止后恢复测试草稿。截图在 `android/verification/`。
- 详细结果：`verification/instrumentation-api36.txt`、`design-upgrade-data-retention.json`、`lint-summary.txt`、`build-info.json`。

### 反馈服务

- **5 项 Python 测试通过**：字段/边界/无公开读取、持久回执与幂等冲突、限流与过期清理、私有文件权限，以及真实 loopback HTTP 往返。
- 额外实际启动 Gunicorn 25.3.0 的两个 worker，通过工厂入口接收 POST，首次返回 201、重试返回 200 且回执相同，随后停止测试进程。结果见 `verification/feedback-gunicorn.json`。
- **尚未部署公网 HTTPS 服务**；APK 的反馈 endpoint 为空。当前提交只会保留草稿并明确提示未配置。Caddy 配置和 Docker 镜像未作生产部署验收。

## 构建和签名

标准环境：完整 JDK 17/21（不能只是 JRE）、Android SDK platform 36 / build-tools 35.0.0；Gradle Wrapper 8.13 附官方分发 SHA-256。

```sh
cd android
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

本机工具放在忽略的 `android/.tooling/`，此次构建实际用：

```sh
JAVA_HOME="$PWD/.tooling/jdk21/Contents/Home" \
GRADLE_USER_HOME="$PWD/.tooling/gradle-home" \
.tooling/gradle-8.13/bin/gradle assembleDebug assembleDebugAndroidTest lintDebug
```

测试只对模拟器执行会重置测试数据库的用例；不要对有用户数据的平板运行测试 APK。调试安装使用本机 AGP debug 证书，私钥没有复制进项目或提交。正式签名只接受本机环境配置，见 `android/README.md`；没有生成或冒充正式签名。

## 当前明确边界

1. **目标硬件未连接**。Xiaomi Smart Pen 1/2、Focus Pen、Focus Pen Pro、REDMI Smart Pen 共用标准输入路径，但没有任何型号被标为“完整适配”。专有轻捏/双击/滑动/笔震动保持未启用；需官方接口与真实事件证据。模拟 MotionEvent 不是硬件实测。
2. 系统手写 IME 的离线能力、系统栏/真实 density、触控范围、长时间延迟和掌触需要用户平板验收。界面已随包提供未修改的官方 MiSans 可变字体（运行时 wght 400/500），指南保留嵌入原字体；部分文件操作/系统权限弹窗仍为 Android 标准样式。Android 与浏览器的字形渲染和系统栏差异仍需真机验收；最终 Logo 已由设计窗口选定并整合。指南当前是可批注 PDF，指南中的设计按钮并未作为原生导航热点接入。
3. 原独立窗口测试包仅初始化指南；本次按用户要求加入其提供的原始论文，正式广泛分发前仍需复核论文再分发权利。指南保留设计窗口内容，其字体/词库等材料均有各自许可，不能把第三方数据统称为 MIT。ECDICT 外部来源链的公开发行复核仍由许可工作负责。
4. 没有真实 GitHub APK Release 和正式证书，无法验收正式发布下载/真实安装权限对话框；本地签名验证与覆盖升级已测。404、限流、网络错误等有真实错误处理路径，未把所有网络分支宣称为线上实测。更换正式证书不能覆盖 debug 安装；先备份、移除开发版、安装正式版并恢复。
5. PDF 大小上限 512 MiB / 10,000 页、渲染位图最长边 2,500 像素、图片不超过 100M 像素；不是所有 PDF 字体/色彩/高级特性均已覆盖。可取消发生在读写/页面之间，当前 PDFBox 解析或原生渲染返回前不能立即中断。真实低空间与超大文档压力验收尚未完成；原子写失败/损坏/取消路径已有软件测试。
6. 备份保存目的地由系统文档提供程序负责；中断时会尝试删除输出，提供程序不支持删除时可能留有不完整文件，不会报告保存成功。页面历史保留 50 步；废纸篓保留内容，没有自动清空策略。
7. 没有公网反馈地址/域名，因此本轮没有发送用户反馈，也没有公网成功回执。生产部署还需服务器、域名、运维保留策略与真实 HTTPS 回验。

## 真机验收步骤（用户下一步）

1. 把下方 APK 复制到平板，由系统打开安装；或 USB 调试后运行 `adb install -r <APK>`。当前 0.1.3-dev 全新书库首次应有五页指南和 11 页论文。记录平板型号、Android/API、屏幕尺寸、density 和系统栏安全区（设置 → 手写笔与设备信息）。
2. 导入自己的一份普通 PDF、一份扫描 PDF、一份加密 PDF及较大 PDF。验证横竖屏、缩放、单/双页、缩略图、选取/复制/查词；扫描页不应伪造可选文字。取消/损坏应有明确反馈。
3. 用 Focus Pen Pro 依次验证四种笔、压感轻重/历史轨迹、手掌同时接触、笔离开/系统取消、橡皮、图形、图片与尺边绘制。记录实际按键/悬停事件，只开启观察到的能力；未收到的专有手势不算通过。
4. 飞行模式下重启应用，查询 `attention`、`学习`、`attenton`，用系统键盘及系统手写输入法输入。确认词库不联网；单独验证输入法本身也可离线工作。
5. 写左右页不同笔迹，新增/复制/旋转/移动/删除页，撤销/重做；强制停止并重启，再重启平板，核对页序、笔迹、图片和进度。测试收藏、文件夹、复制、移动、废纸篓恢复；删除指南后重启不应补回。
6. 通过系统保存/分享导出 PDF，在另一阅读器核对文字、页数、旋转、图片及笔迹。导出 `.annota.zip` 到用户确认的位置，在测试环境恢复并核对内容。确认已有备份后再做卸载/数据清理测试。
7. 当前反馈应明确“未配置，草稿保留”。后续部署 HTTPS 服务后再验证真实回执、失败重试和联系方式私密性。正式 Release 准备后验收说明/下载/取消/权限拒绝/安装及升级数据保留。

## 原独立窗口 APK 产物（0.1.1-dev 历史）

- APK：`android/dist/Annota-0.1.1-dev-debug.apk`
- 体积：75,229,716 字节（71.74 MiB）。
- SHA-256：`63b3f30093327d2706f73ac9b5f92780f29f497b619a6afd4933b3f3f34702a9`。
- Debug 证书 SHA-256：`90e5c38e8278dd1bbc80bb88a2a7255835f687677786aae01aa56ab320b8be18`。
- 已通过 Android apksigner 校验，实际安装到 API 36 模拟器；不是正式签名或发布链接。
- 校验文件：`android/dist/SHA256SUMS.txt`。
- 构建详情：`android/verification/build-info.json`。

## 本次整合产物（0.1.2-dev）

- 本地开发包：`android/dist/Annota-0.1.2-dev-debug.apk`，Git 不提交 APK 二进制。
- 精确体积、SHA-256、开发证书和 UI 证据见 `android/verification/integration-build.json`；`build-info.json` 指向当前构建，原独立窗口记录保存在 `build-info-0.1.1-dev.json`。
- 仍未完成：小米真机及系统手写 IME、公网反馈 HTTPS、生产签名/密钥备份、首个真实 GitHub Release 与目标平板系统安装更新，另有资源权利复核及界面细节收口。
