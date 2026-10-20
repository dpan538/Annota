# 英文界面与 APK 验收

2026-10-21。原生 0.1.4-dev / versionCode 5，API 36 ARM64 专用模拟器；不代表 Xiaomi 真机验收。同一 APK 在首页右上角切换中英文，不拆成两个语言包。

## 界面修正

设置页签原来按字母个数猜宽度，导致 Appearance、Gestures、About 省略。现在按 MiSans 的实际字宽居中，英文页签使用 15 单位文字与 20 单位图标；竖屏页签 13 单位，页脚缩小字号与字距。中文界面的字号沿用原设置。

纸张、笔种、颜色、橡皮、图形、画布提示、设备能力与失败消息随界面语言切换。文档名、中文指南正文和词典释义保留其原始语言；切换语言不改写用户文档。

`EnglishUiTest` 对主页及四类设置检查横竖屏 × 60/68/76 三档，共 30 种布局状态，检查文字省略和实际字形高度；另用实际 Activity/系统输入验证语言切换后重开、新建、工具菜单、反馈失败提示与草稿保留。

主题选中描边原来超过卡片边界，导致右侧被裁掉；现收回预览范围内，横竖屏与三档尺寸共六种状态逐边检查渲染像素。`english-border-before.txt` 保留旧实现右边缺失的失败，`english-border-after.txt` 与完整回归记录修复通过。关于页短句统一为「专为阅读，让思考落在笔尖。」。

## 可以模拟的范围

合成 MotionEvent 覆盖压力变化、真正的历史批次、超范围压力归一化、手掌并发与取消回滚。它验证输入处理逻辑，不能复现笔尖物理曲线、固件采样、系统掌触判断、屏幕刷新和真实延迟。小米笔专有手势与笔震动没有据模拟器结果启用。

`SoakTest` 在本轮候选 APK 上持续渲染 PDF、提交新笔迹、离线查词、读取书库摘要、回收/重建缓存，每十轮销毁并重新打开 Activity，核对保存状态。最终包仅额外修正主题描边与关于页中文短句；持续测试涉及的读取器、存储、PDF、词典及缓存代码哈希相同。持续新增笔迹意味着进程负载也在增长；PSS 不是空载常驻内存，也不能据十分钟模拟器结果承诺数小时真机表现。

平台依据：[Android 手写笔事件与设备校准](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)、[Android 内存模型](https://developer.android.com/topic/performance/memory)。

## APK 与下载

`android/scripts/verify-apk.py` 下载完整副本，核对长度与 SHA-256，再检查 ZIP CRC、入口/包名/版本、随包两份 PDF 与词库哈希、APK 数字签名；可在专用模拟器安装该下载副本并冷启动、检查崩溃记录。默认模式只测试 loopback HTTP；传入实际公开 GitHub 附件 URL 才表示匿名 HTTPS 下载验收。

固定下载入口是 [DOWNLOAD.md](../DOWNLOAD.md)，提供可直接点击的 APK 链接及便于复制到浏览器的完整链接。开发预览使用开发签名，与先前测试包一致；正式生产签名仍待建立。

## 软件内更新在哪里

入口：**设置 → 关于 → 应用更新 → 检查更新**，英文为 **Settings → About → App update → Check update**。

当前读取 `releases/latest` 的正式版本，明确排除 draft/prerelease。下载后校验 SHA-256、apksig 数字签名、包名、版本号和当前安装证书；不匹配就拒绝安装。通过后申请安装来源权限，由 Android 系统安装器覆盖。取消、错误与没有正式 Release 都不伪报成功。

签名与篡改拒绝已有本地夹具回归；当前公开开发预览通过独立下载链接安装，不自动进入稳定更新通道。生产签名、真实更高版本正式 Release、系统权限往返与 Xiaomi 安装器的完整更新链路仍待验收。

## 可复跑

见 [Android 构建与测试](../android/README.md)。仪器测试会改写测试书库，只在无用户数据的专用模拟器运行。截图和 APK 验证记录放在 `android/verification/english-*`；316 轮原始记录、PNG/SVG 曲线、CSV、被测构建身份与可引用描述集中在 [log](../log/2026-10-21-emulator-soak/README.md)。

## 本轮实际结果

- 最终包完整原生回归 **28 项通过、无跳过**，含同开发签名 versionCode 6 更新夹具，错误版本名也被拒绝；另有候选包持续使用测试 1 项通过。反馈服务 5 项通过，lint 0 errors / 54 warnings。
- 持续测试 **602 秒、316 轮、31 次 Activity 重开**，保存状态一致；候选包与最终包哈希分别记录，没有把此记录标为最终包的重复实测。
- 全部采样及重开日志观测到的进程 PSS 为 **127,030–226,946 KiB**；常规采样的 Java 已用堆峰值 76,172,288 bytes、页面缓存峰值 20,283,200 bytes。重开时有短时峰值，随后可下降；不能写成“整个进程恒定 120 MB”或“没有任何泄漏”。完整曲线与原始输出见上述 log。
- 英文主页/设置横竖屏、工具菜单、字典、反馈失败和中英文更新入口的实际截图见 `android/verification/english-*.png`。

## 公开交付回执

已发布 [v0.1.4-dev 开发测试 Pre-release](https://github.com/dpan538/Annota/releases/tag/v0.1.4-dev)，源码目标提交 `02c95fa`。APK 74,591,506 bytes，SHA-256 为 `2ab09c1d4d3a15396cd9a503b2cd5de7cf5cc4ca4b322f4af0fabe401f92707f`。

从公开 URL 不带凭据下载完整 APK 与校验文件，核对哈希、归档 CRC、资源、包名/版本和数字签名后，安装下载副本并冷启动成功，无 AndroidRuntime 崩溃。系统 `am start -W` 观测为 1,864 ms，这是模拟器/debug 的单次操作记录，不是 Xiaomi 冷启动基准或 ≤800 ms 目标的达标证明。

回执：`android/verification/english-apk-public-check.json`、`english-release-public.json`。稳定 `releases/latest` 实际返回 404，符合仅有 Pre-release 的状态；没有把预览冒充正式更新成功。

实际在英文界面点击 Check update，应用访问 GitHub 后显示 `No stable APK release yet (GitHub 404). Try again later.`，没有提供预览覆盖或伪报最新版；见 `english-update-live.json` 与 `english-update-no-stable.png`。此测试不代表更高正式版安装权限往返已经通过。
