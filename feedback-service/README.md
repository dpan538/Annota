# Annota 自动反馈接收服务

Android 点击一次“提交反馈”后，通过 HTTPS 接收层写入 `dpan538/Annota` 的 GitHub Issue，再自动提示结果。没有用户登录、浏览器中转或手动检查按钮。客户端只含接口地址，不含维护者凭据。

## 当前部署

Vercel 项目 [annota-feedback](https://vercel.com/dpan538s-projects/annota-feedback)，生产地址 `https://annota-feedback-dpan538s-projects.vercel.app/v1/feedback`。部署已 READY；用户明确授权后，生产接口已公开，预览仍保留登录保护。用户已配置生产 Secret `NNOTA_GITHUB_TOKEN`，接收服务兼容这个名称。真实 Android 非调试包自动创建 [测试 Issue #2](https://github.com/dpan538/Annota/issues/2) 并自动提示成功，端到端已通过。

将仅限 `dpan538/Annota`、Issues 读写权限的 GitHub fine-grained token 配置为生产 Secret `ANNOTA_GITHUB_TOKEN`，然后重新部署。当前用户已创建的 `NNOTA_GITHUB_TOKEN` 保留原名，代码兼容读取。不要把 token 放入源码、APK、聊天或 `.env` 提交。生产反馈接口需要允许匿名访问，预览仍可保持登录保护。只对这个独立项目设置，不改其他项目或团队安全策略。

`GET /healthz` 只返回服务名和凭据是否配置；不会显示凭据。`POST /v1/feedback` 接收 JSON `body`（1–1000 Unicode 字符）、`requestId`（16–80 字母/数字/连字符）、`version`。未知字段拒绝，不接收联系方式或附件。成功仅返回 `accepted:true`、匹配的 `requestId` 和真实 GitHub Issue `receipt` URL。

GitHub Token 仅发送到固定 GitHub API 地址，禁用重定向；服务不记录消息正文或凭据。请求失败、无凭据、GitHub 拒绝/无效回执均返回错误。超时后不盲目重发 POST，下次同 ID 提交先检查 GitHub 中最近 500 条记录。同实例串行化；不保证多实例同一 ID 同时提交或超过检索窗口后的严格去重。仓库级每小时 20 条保守限流；规模扩大时再接持久存储做全局锁和精细限流。

## 本地验证

```sh
python3 -m unittest discover -s feedback-service -p test_github_relay.py -v
```

8 项测试包含真实本地 HTTP、GitHub 成功、无效回执、失败保留请求身份、超时写入后的恢复、冲突和限流。模拟的 GitHub 不代替公网验收。实际需要验证：Android 提交按钮 → Vercel → GitHub Issue → 自动成功弹窗，以及断网/失败草稿保留。

`api/index.py` 是 Vercel WSGI 入口，`github_relay.py` 是当前实现。旧 `server.py`、Docker/Caddy 与 `test_server.py` 仅保留历史私有 SQLite 接收器，不是当前 Vercel 的入口；旧随机 receipt 不代表 GitHub 收到。
