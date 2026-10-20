# Annota 字标 · 第二轮

三组均为纯白背景、黑色字形，按实际轮廓居中。保留第一轮 MiSans 的方向，并加入两款独立字体作为比较。尚未选定最终标识。

- MiSans 500：比第一轮 700 更轻，略收字距。
- Manrope 600：较紧凑的几何无衬线。
- Bricolage Grotesque 550 / width 85 / optical size 96：采用字体原有宽度轴，无人工横向拉伸。

每组包含 1024 × 1024 PNG 与已转轮廓 SVG，不依赖收件人的字体环境。

```sh
python3 scripts/create-wordmarks.py --fetch-fonts
node scripts/render-logo-concepts.cjs branding/wordmark-refinement
```

Manrope、Bricolage Grotesque 的来源、固定版本和 SHA-256 见 `../fonts/sources.json`，原 OFL 许可随目录保留。下载的原字体不作为本轮图标资源打包。MiSans 使用项目内的官方原始切片。原创视觉按仓库 CC BY-NC 4.0 条款使用；字体本身的许可独立适用。
