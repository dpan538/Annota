# 测试与验收

版本更新先完成构建、lint、核心回归及公开安装包检查，再交给目标平板测试。模拟器通过不等同于小米平板的手感、帧率或触控笔验收。

## 当前版本

- [1.2.0 正式版（本地词典检索升级）](1.2.0-RELEASE-NOTES.md)
- [本次发布验证](../android/verification/release-1.2.0/) · [检索升级记录](../design/dictionary/SEARCH-UPGRADE.md)

## 前一正式版

- [1.1.1 正式版与复核清单](1.1.1-RELEASE-NOTES.md)
- [1.1.1 发布验证](../android/verification/release-1.1.1/) · [离线语义专项](../android/verification/semantic-111/)

## 上一轮预览版

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
python3 design/dictionary/test_lexicons.py
python3 design/dictionary/semantic/build.py
python3 design/dictionary/semantic/finalize.py
```

仪器测试包含会重置书库的夹具，仅在专用模拟器执行。更新测试需要提前准备更高版本、同签名的安装包。私有 PDF 回归不上传文档或其截图；公开展示使用随包示例论文。

动画验收必须检查实际位置、尺寸、连续切换及关闭系统动画后的行为；透明度变化不能作为布局动画通过的证据。笔触检查包含正常阅读比例、放大、压力、重叠、重开及 PDF 导出。词库检查覆盖不同领域、词形、未知片段、断网首次安装与 APK 内资源完整性。

## 历史记录

[1.0.5-preview](1.0.5-PREVIEW-NOTES.md) · [1.0.4-preview](1.0.4-PREVIEW-NOTES.md) · [首版集成验收](INTEGRATION-ACCEPTANCE.md) · [测试索引](../log/README.md)

## 1.1.1 分阶段验证

- [词义排序与反例诊断](../android/verification/sense-ranking-111/)：跨来源候选、独立词形证据、否定/词性拒判、词组边界与逐例语义限制；合成用例和模拟器结果不等于目标平板或总体准确率。

[修复范围与复核清单](1.1.1-RELEASE-NOTES.md) · [测试记录](../android/verification/stable-1.1.1/)。历史候选记录保留各阶段状态；最终交付以本次发布验证为准。

[继续扩词与检索验证](../android/verification/dictionary-expansion-111/)包含来源资产校验、英文释义回退、词形索引、长选区取消与完整句翻译；保留初次失败和最终专项重测记录。模型语义质量限制另见 [离线翻译研究](../design/translation/RESEARCH-NEXT.md)。
