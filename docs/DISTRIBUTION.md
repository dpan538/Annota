# APK 与应用内更新

## 下载渠道

首期通过公开仓库 `dpan538/Annota` 的 GitHub Releases 分发签名 APK。用户选择的首期渠道是 APK，不把小米应用商店作为当前发布依赖。这个方案不免除 Android 安装器或目标地区适用的开发者验证要求；发布前在目标设备确认实际安装流程。

当前已发布 [0.1.4-dev 开发测试预览](https://github.com/dpan538/Annota/releases/tag/v0.1.4-dev)，开发签名，明确标为 Pre-release；[固定下载页](../DOWNLOAD.md) 提供点击下载与复制到浏览器的链接。公开 APK 已匿名完整下载、核对哈希/签名/随包资源，并安装该副本完成冷启动。源码 ZIP 不是 APK。

稳定通道 `/releases/latest` 不包含此预览，目前返回 404；客户端应明确提示尚无正式版本。预览由用户手动下载安装，不改成默认稳定更新。

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
- `latest` 用于正式版本；预览版另设明确 opt-in 渠道，不能把 prerelease 误推给默认用户。
- 只接受白名单仓库的 HTTPS 下载资源。GitHub 可能使用受控资源跳转，下载器要明确处理跳转和超时。
- APK 自带的代码签名用于升级身份校验；SHA-256 用于文件完整性，不能单独替代签名信任。

`versionName` 是展示文字；升级排序以整数 `versionCode` 为准，不直接比较字符串版本号。API 404、限流、超时、网络不可达和用户取消都有独立状态。国内网络不可达时说明原因并允许重试，不显示伪成功。

## 签名与构建

固定应用 ID 与发布签名；密钥由维护者保管并备份。签名密钥、密码、GitHub Token 不进仓库或 APK。不为源码备份制造空的 Release，也不把 debug APK 标为正式版。

后续由 Gradle 生成 release APK，在上传前运行检查、校验签名和迁移测试。若使用 GitHub Actions，签名材料仅通过受保护的 Secrets/Environment 提供，发布需要人工确认。签名密钥的交接与恢复方式是首个正式版本前必须完成的事项。

## 需要后端吗

**更新分发无需业务后端。** GitHub 承担版本信息和 APK 文件托管，客户端负责验证、下载与交给系统安装。

应用内匿名反馈需要单独的最小 HTTPS 接口：校验正文、限制滥用、保存或转交开发者，并返回真实回执。联系方式不得公开写入 GitHub Issue，客户端不得内置仓库写入 Token。接口尚未配置时保留草稿。

## 当前验证与发布前闭环

原生下载/校验/安装入口已有实现；签名验证、篡改拒绝与本地同签名覆盖升级有模拟器记录。开发预览的公开 GitHub 下载 → 专用模拟器安装/冷启动已通过，回执见 `android/verification/english-apk-public-check.json` 与 `english-release-public.json`。生产签名、更高版本正式 Release → 应用内下载 → 安装权限往返 → Xiaomi 安装器的完整更新仍待验收。反馈服务已有源码和本地回执测试，尚未部署公网 HTTPS。

- 同一签名的旧版 → 新版覆盖更新，已有笔记完整。
- 下载损坏、取消、空间不足、安装权限拒绝、签名不符都能退出或重试。
- 离线启动、阅读、书写和查词不发网络请求。
- 论文、字体、词库许可与软件内离线说明齐全。
- 真机验证已记录；未测项目如实列出。

官方参考：[Releases API](https://docs.github.com/en/rest/releases/releases)、[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[APK 独立分发](https://developer.android.com/distribute/marketing-tools/alternative-distribution)、[Android 开发者验证](https://developer.android.com/developer-verification)。
