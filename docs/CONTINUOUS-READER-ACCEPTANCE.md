# 连续阅读、面板与测试版更新验收

2026-10-21，0.1.5-dev / versionCode 6，API 36 ARM64 专用平板模拟器。开发签名，非 Xiaomi 真机性能认证。

## 阅读行为

旧实现只排列当前一页/一组，拖动只改平移量且没有边界；慢拖还会误走选字分支。新实现将所有页组织为连续纵向行，单页一行、双页相邻两页一行。默认适宽，保留整页；横竖方向均限制在文档范围，双指缩放保持焦点位置。

只为视口内页面建立绘制位置，并预取下一行，渲染队列与位图/文字缓存继续限额；10,000 页测试不把所有页面解码进内存。快速翻页操作保留缩放；近距离导航线性滚动，远距离跳转短淡入，避免扫过数千页。

保存当前页、页内偏移、缩放、横向偏移、单/双页及适宽模式。进度只更新数据库的 current 字段，不重写笔迹快照。精细视口属于本机偏好；备份仍保留页码和文档，尚不包含这些偏好。

## 面板与动效

新建、插页和更换模板共用带真实纸张纹理预览的圆角下拉：空白/点阵/格子/横线，白/灰/黑，名称和创建放在同一面板。输入法弹出后面板可滚动并重新定位；监听绑定稳定的宿主窗口，避免首页重排后引用已移除的按钮。

按下反馈 100 ms、面板 120–160 ms、内容/导航 180 ms，使用 LinearInterpolator。工具选中渐显，系统关闭动画时直接切换；惯性滚动采用 Android OverScroller 的物理减速，书写提交没有动画等待。

原始录屏：[阅读滚动](../android/verification/continuous-reader-motion.mp4)、[菜单与页面切换](../android/verification/continuous-menu-motion.mp4)。录屏是模拟器实际编码结果，未补帧或重计时，不是 60 fps 或真机延迟基准。

## 测试版更新

0.1.4 只读 releases/latest 并排除 Pre-release，所以没有正式版本时显示 404；该策略不符合测试用户的升级需求。

0.1.5 的开发/预览包读取最近 30 条 Release，排除草稿，跳过缺少更新清单的历史记录，比较清单里的整数 versionCode；正式生产包继续只读稳定版本。每个可更新 Release 必须附 APK、SHA256SUMS.txt、annota-update.json。

用户主动检查后，展示新版本 → 下载 → SHA-256/数字签名/包名/版本/当前证书验证 → 安装来源权限 → 系统安装器。已验证包和预期身份写入私有偏好，重开后可重试；交给安装器前再次在后台校验。安装完成后清理过期暂存包。没有更高版本时正常提示，不弹出“尚无正式 APK”错误。

**原始 0.1.4 客户端需要先手动覆盖一次。** 服务端发布清单无法改写已安装的旧逻辑；修正后才可在应用内检查测试更新。测试包仍是开发签名，不能据此声称生产签名与 Xiaomi 安装器已经验收。

## 实际证据

- `continuous-instrumentation-api36.txt`：**38 项通过，无跳过**。新增七项连续阅读/输入、实际附着按钮动效，以及两项更新通道/清单回归；同签名 versionCode 7 夹具验证仍通过。
- `continuous-ui-flow.json`：系统 UI 实际创建黑底格子、写入 19 个笔迹点，冷启动后笔迹一致；双页连续阅读的精细位置重开一致。
- `continuous-*.png`：单/双页连续边界、适宽、原生圆角菜单、新建面板横竖屏和英文界面。系统输入法首次教学不作为应用截图归档。
- `continuous-build.json`、`continuous-lint-summary.txt`：最终构建与源码身份，lint **0 errors / 63 warnings**。已有建议及新增自定义 View/原生布局建议尚未全部清除。
- `continuous-apk-local-check.json`：本地完整下载、ZIP/随包资源、签名、安装与冷启动。
- `continuous-apk-public-check.json`、`continuous-release-public.json`：公开 Pre-release 的真实 APK 匿名下载、资源/签名校验、安装下载副本与冷启动；三份附件和更新清单哈希一致。
- `continuous-update-install.json`、`continuous-update-*.png`：真实应用内 GitHub 下载、跨进程恢复、安装来源授权、Android 系统覆盖安装与数据保留。

本次没有重跑先前 316 轮实验；[原始 log](../log/2026-10-21-emulator-soak/README.md) 明确对应 0.1.4。38 项回归及此次界面流程不能替代小米笔手感、掌触和数小时真机内存测试。

## 已发布与实际更新安装

[v0.1.5-dev](https://github.com/dpan538/Annota/releases/tag/v0.1.5-dev) 于 2026-10-21 00:48:42 UTC 公开发布，源码提交 `a1000ed2de858714b24735b2738f5ee4b94d736f`。附件为 APK、SHA256SUMS.txt 和 annota-update.json。最终 APK **74,695,905 bytes**，SHA-256：

```text
a3ddfcad0733732abd432d6ec73eac93a7df19c17dc775b2c593f9f61c8d8b6b
```

在同一个专用模拟器，用修正后源码构建本地低版本客户端 `0.1.4-dev-update-test / versionCode 5`，与公开 versionCode 6 使用同一开发证书。它仅用于制造真实升级条件，不是原始 0.1.4，也未发布。ADB 只负责安装这个起点；**5 → 6 的升级由应用下载公开 APK，再通过 Android 系统安装器完成**。

实际流程：检查更新识别 Pre-release → 确认说明并下载 → 哈希/包名/版本/数字签名/当前证书通过 → 取消安装并强制停止 → 冷启动找回已验证包 → 重试并再次校验 → 系统设置打开“允许此来源” → 返回自动打开安装器 → 点击 Update → App installed → Open。

新版启动就绪后，已安装 base.apk 哈希与公开 APK 完全一致；三份文档状态和精细阅读位置保持相同，黑底格子笔记保留 19 点笔迹，已删除指南仍在废纸篓、样例未补回，待安装记录和缓存已清理。再次检查没有更高版本时正常结束，不再出现旧的稳定通道 404 错误。

两次界面脚本断言做了修正：下载很快，UIAutomator 错过瞬时进度框，直接读到“验证通过”；首次启动尚未完成时过早读取清理状态，待应用就绪后再次核对已清除。均保留在流程说明中，不将界面采样时序当成应用失败，也未改变已发布 APK。默认版本重新构建的 ZIP 内容逐项相同，但包元数据导致整包哈希不同；发布包一直保留最初测试的原件，见 `continuous-rebuild-check.json`。

这完成了开发测试通道的模拟器闭环。原始 0.1.4 仍需手动覆盖一次；生产证书、正式稳定版本、Xiaomi 安装器和目标网络环境仍需各自验收。

平台依据：[OverScroller](https://developer.android.com/reference/android/widget/OverScroller)、[系统动画开关](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled())、[PopupWindow 动画与输入法](https://developer.android.com/reference/android/widget/PopupWindow)。
