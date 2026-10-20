# 持续检查模板

`checks.yml` 已按本地通过的源码检查与离线词典检查编写。初始上传所用 GitHub OAuth 凭据没有 `workflow` 权限，因此此文件保留为模板，目前不会自动触发 Actions。

维护者后续使用具有工作流写入权限的凭据，将其移动至 `.github/workflows/checks.yml` 即可启用。无需修改 APK 或给予应用 GitHub 写权限。

本地运行：

```sh
npm run test:static
python3 design/dictionary/build.py
python3 design/dictionary/test_lookup.py
```
