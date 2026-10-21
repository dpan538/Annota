# 文档搜索、选区与轻量导航

文内搜索、同页选区手柄、PDF 目录与页书签共用既有阅读器。搜索退出恢复视口与笔型，文内跳转提供临时返回入口；选词只显示「复制｜查词」文字按钮。

阅读模式中央留空，左右两侧为图标；显示预设移入右侧更多。演示使用原始《Attention Is All You Need》，对应 `navigation-paper-*.png`。双栏等合成 PDF 只用于边界回归。

[验收说明](../../docs/DOCUMENT-NAVIGATION-ACCEPTANCE.md)记录行为与范围；`android/verification/navigation-*` 保存本轮最终包的构建、回归、实际键盘与公开交付证据。早期版本证据保持其原身份。

最终包通过 78 项原生回归和四项独立视觉/实际键盘流程，无跳过；88 张原生界面截图归档，lint 0 errors / 78 warnings。
