# sources-build/：仅构建期使用的词库源

这些文件用于生成 `bundle/*.sqlite`，**不进入 APK**（`android/scripts/sync-assets.py` 只同步
`design/dictionary/sources/`）。它们随仓库提交，保证在有网络前也能离线重建词库。

| 文件 | 来源（版本） | 许可 |
| --- | --- | --- |
| `oewn-2025.xml.gz` | [Open English WordNet 2025](https://github.com/globalwordnet/english-wordnet/releases/download/2025-edition/english-wordnet-2025.xml.gz)（WN-LMF 1.3） | CC BY 4.0 |
| `ili-map-pwn30.tab.gz` | [CILI](https://raw.githubusercontent.com/globalwordnet/cili/master/ili-map-pwn30.tab)（PWN30 ↔ ILI） | CC BY 4.0 |
| `wn30-synsets.tsv.gz` | Princeton [WordNet 3.0](https://wordnet.princeton.edu/) `data.*` 派生 | WordNet 3.0 |
| `wn30-sense-order.tsv.gz` | WordNet 3.0 `index.sense` 的 sense_number 频率序 | WordNet 3.0 |
| `omw-cwn.tsv.gz` | [OMW `wns/cwn/wn-data-qcn.tab`](https://github.com/omwn/omw-data/blob/main/wns/cwn/wn-data-qcn.tab)（Chinese WordNet） | CWN 自定义许可 |
| `omw-wikt-cmn.tsv.gz` | [OMW `wns/wikt/wn-wikt-cmn.tab`](https://github.com/omwn/omw-data/blob/main/wns/wikt/wn-wikt-cmn.tab) | CC BY-SA 3.0 |
| `opencc-tscharacters.txt.gz` | [OpenCC `data/dictionary/TSCharacters.txt`](https://github.com/BYVoid/OpenCC/blob/master/data/dictionary/TSCharacters.txt) | Apache 2.0 |

重建/刷新方式见 [`../build-sources.py`](../build-sources.py)。所有 gzip 使用 `mtime=0`；
同一输入两次生成的 SHA-256 相同。上游 URL、版本、许可与逐文件 SHA-256 也写入
`bundle/manifest.json` 的 `buildSources` 与 `lexicons` 字段，构建时由 `lexicons.report()` 复核。
