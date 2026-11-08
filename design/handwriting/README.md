# 手写体验子系统（1.2.1 候选）

本轮不是调参，而是五个从零设计的子系统；核心书写体验代码自有，开源只用作基础设施或对照。

| 子系统 | 文件 | 解决的问题 | 关键设计 |
| --- | --- | --- | --- |
| IEA 接触意图仲裁 | `ContactIntent.kt`、`MainActivity.kt` | 手臂大面积接触边缘被系统当成返回手势，误退出阅读器 | stylus/finger/limb 分类；大面积接触消费事件；仅在笔/手臂活动时动态排除左右边缘；接触后首次返回延迟 |
| ITL 笔迹事务账本 | `InkLedger.kt`、`Store.kt` | 抬笔同步整本 JSON+gzip+SHA+SQLite 阻塞 UI，连续快写断触 | 每书 append-only 二进制日志（CRC、页 op、zigzag 定点坐标）；后台单次序列化快照；UI 仅 snapshot+版本预留；undo/export/close 前 flush |
| SCR 连续性重建 | `StrokeContinuity.kt` | 输入丢包/掌臂干扰导致笔画中断 | 时间缺口检测；曲率受限三次 Hermite；只补两项真实采样之间的缺口，不跨抬笔、不外推 |
| NPDE 墨流动力学 | `InkDynamics.kt`、`InkGeometry.kt`（v6） | 连笔接缝细、阻尼感不足 | 固定步长连续时间墨腔/流量/吸收/摩擦模型；宽度/浓度由状态驱动；旧 v4/v5 位级不变；屏幕/PDF 共用 |
| PRC 预测-校正 | `InkPredictor.kt`、`ReaderView.kt` | 跟手性不足，单纯预测会过冲 | 自写短时曲率外推 + 置信度 + 误差校正；预测只画活动笔迹层，不写 Mark/Store/导出 |

验证：`ContactIntentTest` 4、`ArmGuardTest` 1、`InkLedgerTest` 6、`StrokeContinuityTest` 7、
`InkDynamicsTest` 12、`InkPredictorTest` 8、`WritingExperienceAcceptanceTest` 3；全量 gate 271/271。
验收图表与前后对比见 `android/verification/dictionary-1.2.1-candidate/acceptance/README.md`。

已知边界：以上均为 API 36 ARM64 模拟器（ranchu）合成输入结果；真机（Xiaomi Pad 9 Pro Max）的笔尖-墨迹延迟、
手臂接触形态、触觉与长时间书写稳定性仍需实测。预测点绝不进入保存/导出；SCR 不跨抬笔；v6 新笔迹不被旧版本读取。
