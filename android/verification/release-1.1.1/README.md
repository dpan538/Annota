# Annota 1.1.1 正式版验证

最终候选已通过构建、lint（0错误，100警告）、签名与全部随包资产校验；专用断网 ARM64 模拟器完整回归 **183 / 183 通过**。

- [原始回归](regression.txt) · [校验汇总](validation.json) · [APK归档/资产/证书校验](apk-archive.json)
- [向量、缓存和语义诊断](../semantic-111/) · [完整更新与真机复核](../../../docs/1.1.1-RELEASE-NOTES.md)
- 公开 Release/APK 下载校验在发布后记录至 release.json 与 public-apk.json；不把回环下载冒充公开分发。

测试仅操作 emulator-5584，保留其应用数据覆盖安装；未操作维护者实体平板。语义诊断保留未正确排序的例子，不宣称整体消歧准确率。
