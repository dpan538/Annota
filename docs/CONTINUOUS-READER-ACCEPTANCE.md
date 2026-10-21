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

本次没有重跑先前 316 轮实验；[原始 log](../log/2026-10-21-emulator-soak/README.md) 明确对应 0.1.4。38 项回归及此次界面流程不能替代小米笔手感、掌触和数小时真机内存测试。

公开 APK 与真实应用内安装的回执在发布后补入本页。

平台依据：[OverScroller](https://developer.android.com/reference/android/widget/OverScroller)、[系统动画开关](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled())、[PopupWindow 动画与输入法](https://developer.android.com/reference/android/widget/PopupWindow)。
