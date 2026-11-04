# 1.1.1 本地语义重排验证

2026-11-04。最终签名 Release APK 在专用 Android 16 / API 36 ARM64 模拟器 emulator-5584 上断网运行完整回归：**183 项通过，339.201 秒**。这是自动化验证，不是 Xiaomi Pad 9 Pro Max 真机手感测试。

[完整原始回归](regression-final.txt) · [状态、APK 和耗时](validation.json) · [模型资产构建与许可](../../../design/dictionary/semantic/)

## 覆盖范围

- 13 条官方 tokenizer 样本、9 条同模型 batch1 嵌入对照，CPU1/2/4 线程结果一致性。
- 原生模型显式128 token上限、关闭生命周期；新服务实例复用SQLite向量，内容变化重新计算，同句跨选词共享缓存。
- 前台查词实际等待重排、取消旧查询；后台让出资源、关文档取消；设置行默认状态、真实点击与持久状态，关闭向量后普通离线查词保留。
- 真实 PDF 取词、菜单取消、沉浸栏无占位、笔迹/导出/签名/渲染/导航及更新解析回归。

## 语义与时间

[36 条开发诊断](sense-ranking-diagnostics.json)和[20 条扩展诊断](semantic-heldout-diagnostics.json)逐条记录输出和失败。20条最初独立编写，但后续已观察其输出并用于修正通用弱语境/同义竞争/限定义项门禁，**不能继续称为盲测，也不能据此报告总体准确率**。人工规则、普通排序、短语命中与向量提升须分别判断。

[逐条复核](../../../design/dictionary/semantic/semantic-review.json)列出仍不合适的默认义项，例如 seal 和 tissue，及源词义映射限制。模型分数不是正确概率；全部测试通过不表示每条译义正确。

[线程对比](semantic-thread-benchmark.json)仅测一个固定英文句子、每配置8次推理，使用已安装模型。最终采用双线程；端到端诊断是温进程、含持久缓存的查询，不能当作首次安装或真机p95。

保留的 regression-cache-assertion.txt / regression-threshold-and-startup-race.txt 是中间失败：前者把正常缓存复用错判为必须重新编码；后者包含过严门槛和设置测试未等启动IO的竞态。最终修正后重跑完整套件，未以这些中间结果发布。
