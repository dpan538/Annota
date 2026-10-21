# 三星 S Pen 经验与本轮采用范围

2026-10-21。依据公开官方资料，而非对 Samsung Notes 闭源实现的推测。

三星研究人员在 Galaxy Z Fold3 的访谈中明确说明，低延迟方案结合了落点预测和前缓冲渲染。[Samsung Research 访谈](https://news.samsung.com/in/interview-video-a-collaboration-story-told-by-a-samsung-ukraine-researcher-how-the-s-pen-for-foldables-evolved-with-ai-technology)。这是值得借鉴的方向，不能据此声称第三方应用已经获得三星的设备延迟或内部算法。

Samsung 的 S Pen Remote SDK 公开功能主要是侧键和空中移动事件，并依赖特定三星系统模块；它不是通用的 PDF 高清渲染或跨品牌触控笔引擎。[S Pen Remote SDK](https://developer.samsung.com/galaxy-spen-remote/s-pen-remote-sdk.html)。本项目仍从 Android MotionEvent 获取真实坐标、压力、历史采样和指针身份，适用于符合标准的三星、小米等设备。

本轮实际加入：

- 触控笔落下时调用 `requestUnbufferedDispatch`，请求系统即时交付笔事件；手指翻页仍使用正常事件合批。平台文档明确推荐此方式减少输入等待。[Android 触控笔与低延迟绘制](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)。
- 绘制笔迹时改为按索引遍历相邻点，避免每次绘制建立与笔迹长度相同的临时 Pair 列表，降低书写期间的分配量。没有以此虚构帧率或毫秒收益。
- 保留历史压力与坐标，并补上 ACTION_UP 中尚未出现在 MOVE 里的最后一个真实点，避免快速短笔画尾部缺失。测试覆盖合批历史点与压力顺序、快速抬笔终点、掌触先落下、指针索引变化、尺边锁定和临时擦除。
- PDF 保持原始文件，显示时按可见区域从 PDF 重新渲染，批注继续保留矢量坐标；固定低分辨率整页预览只承担加载过渡。Samsung Notes 官方也采用导入副本并在 PDF 上叠加书写的产品流程，但公开支持文档没有披露它的缓存或分块算法，因此不把本项目实现归因于三星内部方案。[Samsung Notes PDF 导入与导出](https://www.samsung.com/us/support/answer/ANS10002404/)。

前缓冲与预测仍需独立实验：Android 官方指出前缓冲适合局部笔迹，不适合整屏平移/缩放，否则可能撕裂；预测点必须随后被真实点替换，不能写进最终笔迹或导出文件。本轮没有启用未经验证的预测或前缓冲管线。后续需在目标平板上测笔尖到墨迹延迟、急转折过冲、旋转/缩放、掌触和笔尾擦除，再决定是否切换管线。

模拟事件与模拟器能确认输入数据完整、UI 状态与绘制结果，不能代替 Xiaomi/Samsung 真机手感和延迟测量。
