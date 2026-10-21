# APK 与应用内更新

## 下载渠道

首期通过公开仓库 `dpan538/Annota` 的 GitHub Releases 分发签名 APK。用户选择的首期渠道是 APK，不把小米应用商店作为当前发布依赖。这个方案不免除 Android 安装器或目标地区适用的开发者验证要求；发布前在目标设备确认实际安装流程。

历史 0.1.5 开发预览为 [0.1.5-dev 开发测试预览](https://github.com/dpan538/Annota/releases/tag/v0.1.5-dev)，开发签名，明确标为 Pre-release；[固定下载页](../DOWNLOAD.md) 提供点击下载与复制到浏览器的链接。0.1.5 的真实公开 APK 已通过匿名下载、资源/签名校验、安装和冷启动，见 `android/verification/continuous-apk-public-check.json`。源码 ZIP 不是 APK。

0.1.5 开发/预览包读取最近 30 条 Release，包括非草稿 Pre-release，以清单的整数 versionCode 选择更高版本；正式生产包保持 `/releases/latest` 稳定通道。没有更高版本时正常提示。原始 0.1.4 因旧逻辑限制需要手动覆盖一次，之后可在应用内检查测试更新。

## 0.1.8 当前测试版

当前下载为 [0.1.8-dev](https://github.com/dpan538/Annota/releases/tag/v0.1.8-dev)，versionCode 9，使用原开发证书的 Pre-release。独立纯阅读与三个精简预览卡保持只读和阅读位置；详见[验收](READING-MODE-ACCEPTANCE.md)。63 项原生回归、四项独立视觉/界面流程通过；三附件摘要及公开完整下载/安装/冷启动回执为 `android/verification/reading-*public*.json`。原始公开 0.1.7 通过原生入口与实际 Android 安装器完成 8 → 9，文档/进度/外观及阅读偏好保持，见 `reading-update-install.json`。历史版本附件与证书连续性保留，不改变正式稳定通道。

## 0.1.7 历史测试版

该次下载为 [0.1.7-dev](https://github.com/dpan538/Annota/releases/tag/v0.1.7-dev)，versionCode 8，继承 0.1.6 本地候选内容并补齐三档控件和稳定菜单。保持同一开发签名，真实 APK、SHA256SUMS.txt 与 annota-update.json 三附件公开可读；完整匿名 APK 下载、签名/资源、安装与冷启动通过，见 `android/verification/ui-sizing-apk-public-check.json`。57 项原生回归、两项完整视觉流程与源码身份见[本轮验收](UI-SIZING-ACCEPTANCE.md)。原始公开 0.1.5 的原生检查、下载、校验及真实 Android 安装器升级 6 → 8 也已通过，文档状态、元数据、阅读位置和外观偏好保持，见 `ui-sizing-update-install.json`。不是正式生产签名稳定版。

## 用户流程

1. 第一次在 GitHub Release 下载 APK，并在 Android 系统界面安装。
2. 应用「设置 → 关于 → 检查更新」主动查询发布信息。
3. 展示新版本、版本说明、包大小与下载入口，由用户确认下载。
4. 下载到应用暂存目录，支持取消、失败重试与临时文件回收。
5. 验证 SHA-256、预期包名、versionCode 与签名证书；拒绝不一致或损坏包。
6. 若需要，进入系统“允许此来源安装”的授权页；回来后继续同一流程。
7. 通过系统安装器覆盖安装。完成后保留文档、笔迹、词库与偏好；迁移需可恢复。

不静默安装，不自动卸载旧应用，不通过卸载重装来规避签名不一致。更新失败应保留现有可用版本和本地数据。

## GitHub 端

- 正式标签：`vX.Y.Z`，附清晰的版本说明。
- 附件：签名 `Annota-X.Y.Z.apk`、`SHA256SUMS.txt`，以及客户端必需的 `annota-update.json`（由 `android/scripts/make-update-manifest.py` 从真实签名 APK 生成）。
- 使用公开 Releases API：`GET https://api.github.com/repos/dpan538/Annota/releases/latest`，没有 Token 也能读取公开发布信息，但需处理限流。
- `latest` 用于正式生产版本；安装开发/预览包的用户进入测试通道，允许 Pre-release，生产包不默认推送预览。
- 只接受白名单仓库的 HTTPS 下载资源。GitHub 可能使用受控资源跳转，下载器要明确处理跳转和超时。
- APK 自带的代码签名用于升级身份校验；SHA-256 用于文件完整性，不能单独替代签名信任。

`versionName` 是展示文字；升级排序以整数 `versionCode` 为准，不直接比较字符串版本号。API 404、限流、超时、网络不可达和用户取消都有独立状态。国内网络不可达时说明原因并允许重试，不显示伪成功。

## 签名与构建

固定应用 ID 与发布签名；密钥由维护者保管并备份。签名密钥、密码、GitHub Token 不进仓库或 APK。不为源码备份制造空的 Release，也不把 debug APK 标为正式版。

后续由 Gradle 生成 release APK，在上传前运行检查、校验签名和迁移测试。若使用 GitHub Actions，签名材料仅通过受保护的 Secrets/Environment 提供，发布需要人工确认。签名密钥的交接与恢复方式是首个正式版本前必须完成的事项。

## 需要后端吗

**更新分发无需业务后端。** GitHub 承担版本信息和 APK 文件托管，客户端负责验证、下载与交给系统安装。

当前反馈使用 GitHub 公开 Issue：系统浏览器登录并确认提交，App 返回后读取真实 Issue 回执，核对成功才清空对应草稿。离线、尚未提交、GitHub 不可达均保留草稿并提示。不需要业务后端，也不在 APK 中放仓库写入 Token；没有匿名直传能力。

## 当前验证与发布前闭环

0.1.5 已完成测试通道闭环：本地低版本修正客户端（versionCode 5）识别公开 Pre-release → 应用下载 versionCode 6 → 校验 → 杀进程重开恢复待安装包 → 系统来源授权 → Android 安装器覆盖升级。安装后 APK 哈希与公开原件相同，笔迹、精细阅读位置、废纸篓和初始化标记不变，暂存已清理。起点是修正源码的测试夹具，不能据此声称原始 0.1.4 能直接升级；原始用户需手动覆盖一次。证据见 `android/verification/continuous-update-install.json` 与[验收记录](CONTINUOUS-READER-ACCEPTANCE.md)。

0.1.4 的旧公开下载和稳定通道 404 保留为历史记录。生产签名、更高版本正式稳定 Release、安装权限拒绝及 Xiaomi 安装器仍需另行验收。旧私有反馈接收器源码保留为历史参考，当前安卓反馈不调用该接收器。目标设备真机测试由用户反馈通过；本轮新增功能的自动化证据单列，不替代微信、QQ 与不同厂商桌面的实机测试。

- 同一签名的旧版 → 新版覆盖更新，已有笔记完整。
- 下载损坏、取消、空间不足、安装权限拒绝、签名不符都能退出或重试。
- 离线启动、阅读、书写和查词不发网络请求。
- 论文、字体、词库许可与软件内离线说明齐全。
- 真机验证已记录；未测项目如实列出。

官方参考：[Releases API](https://docs.github.com/en/rest/releases/releases)、[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[APK 独立分发](https://developer.android.com/distribute/marketing-tools/alternative-distribution)、[Android 开发者验证](https://developer.android.com/developer-verification)。

## 1.0.0 首个稳定版的签名决定

用户明确选择沿用当前证书，以保留从开发版覆盖升级的能力。1.0.0 使用非调试 release 构建；证书 SHA-256 保持 `90e5c38e8278dd1bbc80bb88a2a7255835f687677786aae01aa56ab320b8be18`，没有生成新证书或要求卸载迁移。同一私钥转存为本机权限受限的发布配置，原密钥及发布配置只允许维护者读取；密钥、密码和仓库写入凭据均不入库、不打入 APK。

维护者本机发布配置位于被 Git 忽略的 `android/.tooling/signing/`；后续仍通过 `ANNOTA_KEYSTORE`、`ANNOTA_STORE_PASSWORD`、`ANNOTA_KEY_ALIAS`、`ANNOTA_KEY_PASSWORD` 四个环境变量使用 Gradle release 构建。保管并备份这个签名身份；更换证书将影响覆盖更新。

内部 0.2.0 里程碑对外统一为 1.0.0（versionCode 11），Release 与 APK 均使用这个名称。精确身份和实际升级证据见 [首版验收](INTEGRATION-ACCEPTANCE.md)。
