# Annota 反馈接收层

尚未部署线上服务，没有真实公网回执。Android 默认 endpoint 为空：失败或未配置时保留草稿，不声称已发送。

只接收 `POST /v1/feedback` JSON：`body`（1–1000 Unicode 字符）、`contact`（0–120）、`requestId`（16–80 位字母/数字/连字符）。未知字段拒绝。不接收附件、截图、日志或设备信息；没有 GitHub Token，没有公开读取反馈接口。成功写入 SQLite 后返回 `{"accepted":true,"receipt":"真实随机回执"}`；相同请求 ID 与正文重试返回相同回执，正文冲突返回 409。

## 本地验证

```sh
cd feedback-service
python3 -m unittest -v
```

测试使用临时 SQLite 和真实本地 HTTP 连接；它不是公网 HTTPS 验收。

## 部署

在有可用域名与 80/443 端口的服务器上设置 `FEEDBACK_HOST`、至少 32 字符随机 `FEEDBACK_SECRET`，运行 `docker compose up -d --build`。Caddy 负责 HTTPS；服务端口不映射至宿主。数据库卷不可公开，联系方式只供维护者在服务器内访问。默认保留 90 天，接收下一条提交时清理过期记录；需要精确到时的删除由运维另行运行清理。限制每小时 8 条；当前按直接传输对端做 HMAC，因此反向代理后限制对所有用户合并生效，是保守小规模部署默认值。不要直接信任客户端 X-Forwarded-For；多用户扩容时需配置受信代理限流。

验证真实部署：POST 后检查返回的 receipt 和私有数据库记录，再以 Android `-PfeedbackEndpoint=https://你的域名/v1/feedback` 构建。不要把示例域名作为实际服务地址写入 APK。应用端拒绝 HTTP 和重定向。服务端不写请求体访问日志，部署方需保护反向代理日志和持久卷。
