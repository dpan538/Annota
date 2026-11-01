# 测试与验收

版本更新先完成构建、lint、核心回归及公开安装包检查，再交给目标平板测试。模拟器通过不等同于小米平板的手感、帧率或触控笔验收。

## 当前版本

- [1.0.7-preview 修复与验证](1.0.7-PREVIEW-NOTES.md)
- [1.0.7-preview 真机测试清单](BETA-1.0.7-PREVIEW-CHECKLIST.md)
- [自动化原始记录](../android/verification/preview-1.0.7/)

## 开发检查

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
cd ..
python3 design/dictionary/build.py
python3 design/dictionary/test_lookup.py
```

仪器测试包含会重置书库的夹具，仅在专用模拟器执行。更新测试需要提前准备更高版本、同签名的安装包。私有 PDF 回归不上传文档或其截图；公开展示使用随包示例论文。

动画验收必须检查实际位置、尺寸、连续切换及关闭系统动画后的行为；透明度变化不能作为布局动画通过的证据。笔触检查包含正常阅读比例、放大、压力、重叠、重开及 PDF 导出。词库检查覆盖不同领域、词形、未知片段、断网首次安装与 APK 内资源完整性。

## 历史记录

[1.0.5-preview](1.0.5-PREVIEW-NOTES.md) · [1.0.4-preview](1.0.4-PREVIEW-NOTES.md) · [首版集成验收](INTEGRATION-ACCEPTANCE.md) · [测试索引](../log/README.md)
