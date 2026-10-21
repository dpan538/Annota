# 连续阅读与测试版更新

本轮修正用户在 Xiaomi 平板试用后报告的连续滚动缺失、页面漂移、面板朴素、交互无过渡和测试版更新报 404。

实现、取舍、38 项回归及实际界面流程见[验收记录](../../docs/CONTINUOUS-READER-ACCEPTANCE.md)。原始截图、未补帧的 MP4、状态对比、构建与更新回执位于 [Android verification](../../android/verification/README.md) 的 `continuous-*` 文件。

这轮与旧的 316 轮持续使用实验分开归档；不复用旧版本性能结论。

开发测试 [v0.1.5-dev](https://github.com/dpan538/Annota/releases/tag/v0.1.5-dev) 已公开，源码提交 `a1000ed2de858714b24735b2738f5ee4b94d736f`，APK 与校验清单均可匿名下载。原生应用实际识别 Pre-release、下载并验证、强制停止后找回待安装包、进入系统来源授权并返回安装器，最终覆盖安装公开原件。三份状态、19 点笔迹、阅读位置和指南废纸篓标记相同；暂存清理。

起点为修正后源码的本地低版本夹具（versionCode 5），不是原始 0.1.4。原始版本需手动覆盖一次；这轮没有将测试包改称正式版，也不代表 Xiaomi 系统安装器已验收。详细阶段时间与哈希见[真实更新回执](../../android/verification/continuous-update-install.json)。
