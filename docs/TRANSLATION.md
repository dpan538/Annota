# 可选的英汉句子翻译

单词和词组优先使用 Annota 随包词库，不需要下载或初始化翻译 SDK。选中的长文本完整保留；最多翻译 4,000 个字符，超过时提示缩短选区，不静默截断。

句子翻译由 [Google Translate](https://translate.google.com) 提供，通过 ML Kit 在设备上完成，支持英语与中文互译。用户在现有字典面板选择翻译后，先检查本机模型；模型缺失时，需要另行确认通过 Wi-Fi 下载约 30 MB 的翻译包。下载和翻译都有状态提示，失败后可以重试，原有离线词库仍可使用。模型下载完成后，句子翻译可在断网时使用。

根据 [ML Kit 隐私说明](https://developers.google.com/ml-kit/terms)，原文和译文在设备上处理，不发送到 Google 服务器。ML Kit 可能联网下载模型、接收更新或兼容性信息，并向 Google 发送 API 使用和性能指标。普通词库查询不初始化翻译 SDK；翻译功能也不把文本转交远程翻译接口。关闭面板会停止结果更新并释放翻译客户端，但已发起的模型下载可能继续。

首次使用需要联网下载模型，下载能否完成取决于设备和网络对 Google 服务的可达性，不保证所有 Android 设备或中国大陆网络都能完成下载。模型未就绪时会明确提示，不显示虚构译文。

这是通用机器翻译，可用于粗略理解句意，不能视为已验证的学术翻译。实际探测中，`recurrent layers` 被译为“反复间隔”，注意力机制语境中的 `attends` 被理解为“参加”，说明技术术语仍可能错译。查词优先使用随包词组库；句子译文中的术语、长从句、否定和数量关系需要结合原文核对。

## 验证范围

模型下载、断网推理和回归测试结果记录在[手写与查词验收记录](HANDWRITING-LOOKUP-ACCEPTANCE.md)。本轮专用 Android 模拟器已通过实际模型下载与断网双向翻译探测；这证明该测试环境的功能链路可用，不代表译文质量已经全面验证。Xiaomi Pad 9 Pro Max 的模型下载、实际译文和断网体验仍待真机验证，模拟器结果不替代目标设备验收。

## 归属与许可

翻译由 [Google Translate](https://translate.google.com) 提供；使用 [ML Kit 端侧翻译](https://developers.google.com/ml-kit/language/translation)，相关 [Cloud Translation 说明](https://cloud.google.com/translate)。

结果旁的 `google_translate_attribution*.png` 来自 [Google 官方标识包](https://docs.cloud.google.com/static/translate/images/google-translate-attribution.zip)，保持原始图像与纵横比例，不属于 Annota 的 MIT 许可。其使用遵循 [翻译归属标识要求](https://cloud.google.com/translate/attribution) 和 [ML Kit 翻译使用规范](https://developers.google.com/ml-kit/language/translation/translation-terms)。

Google 的译文免责声明涵盖准确性、可靠性、适销性、特定用途适用性和不侵权等明示或默示担保；该机器翻译不提供这些保证。[完整原文与要求](https://cloud.google.com/translate/attribution#disclaimer)

其他资料：[Android 接入](https://developers.google.com/ml-kit/language/translation/android)、[ML Kit 隐私说明](https://developers.google.com/ml-kit/terms)。
