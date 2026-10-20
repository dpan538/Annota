# Annota 标识

唯一初始 Logo：用户选定的 **3B**。Outfit 600，字距 −0.03em；黑色字母中仅 **t** 使用现有「蓝调」的钴蓝 `#4460CE`。

`annota-logo.svg` 是转轮廓的矢量母版。1024 × 1024，白底圆角半径 192，字形实际边界中心为 (512, 512)，字标宽 800。PNG 保留圆角之外的透明区域。

```sh
npm run logos:render
```

无需安装字体即可使用或渲染母版。字体来源、固定版本及原文件哈希见 `fonts/sources.json`；原字体软件使用 OFL，原创标识使用根目录 [CC BY-NC 4.0](../LICENSE-VISUALS.md)。Android 资源只是同一标识的尺寸与系统遮罩适配。

未采用的候选已从当前项目移出；历史提交中仍可追溯早期设计。

安卓派生资源：`python3 android/scripts/prepare-branding.py`。启动图标留出系统安全区；Android 系统控制遮罩形状。Android 13+ 单色图标沿用同一字形，按用户系统主题着色。母版 PNG 本身始终是圆角白底和钴蓝 t。

2026-10-21：正式 Logo 保持原版钴蓝 t；首页同字形采用全黑，仅为场景适配。铅笔替换尝试已放弃。
