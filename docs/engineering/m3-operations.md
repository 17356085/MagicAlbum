# M3 媒体与通知运行手册

## 启用与归属

Java 拥有 `media_asset`、`media_processing_job`、`media_request_outbox` 及对象存储权限。Go 通过独立服务令牌和任务租约读取确定原图、生成预览、回传结果，不连接业务数据库，不持有 S3 凭据。消息和回传字段见[执行契约](m3-runtime-contract.md)。

原有 Compose 默认关闭媒体处理，旧上传继续同步返回原图 URL。启用时，在私有 `.env` 设置独立随机的 `MEDIA_INTERNAL_SERVICE_TOKEN`（32 随机字节，与通知令牌分开）及 `MEDIA_AMQP_URL`。AMQP URL 的用户名和密码须百分号编码，主机使用 `rabbitmq:5672`。

```sh
docker compose --env-file .env -f docker-compose.yml -f docker-compose.media.yml config --quiet
docker compose --env-file .env -f docker-compose.yml -f docker-compose.media.yml up -d --build
```

默认本地存储使用持久化上传卷。S3 模式完整设置 `S3_BUCKET`、`S3_REGION`、`S3_ACCESS_KEY`、`S3_SECRET_KEY`；兼容服务另设 `S3_ENDPOINT`。`S3_PUBLIC_BASE_URL` 留空时使用应用稳定地址和读取时签名；设置时应指向已配置好的公开图片/CDN 地址。切换默认 provider 不会自动迁移已有对象，旧 provider 的目录、凭据和读取路径需继续保留。

迁移增加媒体表、租约、输出与投递字段，以及通知库 V4 事件日志。保留数据库与图片对象的配套备份；不要删除 Flyway 历史、重建用户数据或清空对象桶来解决启动失败。

## 用户状态与通知

原图上传成功立即可发帖或回复，JPEG/PNG 同时创建 preview。GIF/WebP 沿用当前服务实际支持的原图能力，不创建 Go 预览；动画 PNG 保留原图并明确报告预览失败。不会静默重写 Markdown。

手机和桌面可展开图片状态、查看原图/已接受预览、停止任务、重试失败任务。浏览器按账号最多保存 20 条记录，后端 owner 查询是权威状态。用户重试创建新的 jobId、eventId 与递增版本，旧回调不能覆盖新任务。切换账号、隐藏页面和卸载会中止对应连接或请求。

通知创建/标记已读与 owner 事件同事务提交。每个账号串行分配游标，避免自增 ID 与提交顺序不同造成遗漏。SSE 只传变化标识，界面再读取通知列表；重连携带 `Last-Event-ID`，也可调用 `/api/v1/notifications/events?after=...` 补拉。新会话先重新同步列表，不将浏览器缓存当作通知内容。

连接最长 55 秒且不超过 JWT 到期时间，每 2 秒心跳；单账号最多 2 条、单实例最多 128 条连接，超额 429，客户端退避重连。仅 stream 路由放宽至 65 秒，Nginx 对该路径关闭缓存和缓冲。当前验收目标为单实例，不代表容量或多实例故障切换通过。

## 故障、清理与回退

| 情况 | 行为 |
| --- | --- |
| Broker 停机 | 原图上传继续，SQL Outbox 保留请求，确认失败后退避重试。 |
| Go 退出或重启 | 未确认消息重投，持久化租约到期后重新领取。 |
| 重复消息 | 终态直接确认；执行中延迟重试，不创建额外任务。 |
| 结果响应丢失 | 重新领取读取持久化终态；相同租约和相同结果回传幂等。 |
| 图片永久错误 | 固定错误代码入库，保留原图，可用新版本重试。 |
| 存储暂时不可用 | 保留任务和候选元数据，不把网络错误写成永久图片错误。 |
| 原图写入成功但提交未确认 | 保留 UPLOADING 意图，按位置、长度和 SHA-256 对账后建立任务。 |
| SSE 断开或游标无效 | 退避重连，必要时重新读取普通列表。 |

默认租约 120 秒，可配置为 10 秒至 10 分钟。worker 依据每次返回的实际到期时间动态续租，间隔取剩余租约约三分之一且不超过 30 秒；结果回传期间也续租。恢复器每 15 秒检查到期的 PROCESSING 任务，PENDING 任务仍有 120 秒宽限期；以原 eventId 再投，不创建新业务事件。执行最多 5 次，耗尽为 FAILED/TIMEOUT，owner 重试另建版本。Outbox 的 FAILED 是发布待重试，与任务永久失败含义不同。

结果通过短事务预留候选、事务外写对象、短事务接受。清理默认保留 24 小时（最少 20 分钟），每批扫描最多 100 个条目；只清理规范派生对象，有效候选/已接受输出受同一任务行锁保护，未知任务的对象保留，原图不自动删除。对象对账/清理使用独立线程，不占媒体发布或普通通知调度线程。

回退使用当前二进制关闭 `MEDIA_PROCESSING_ENABLED` 与 `MEDIA_REGISTRATION_ENABLED`，停止 media-worker，再重启核心；保留新表、对象及 provider 配置。重新启用后处理积压，不降级数据库，也不承诺旧二进制可读取未来迁移。

## 排查与验收

```sql
SELECT status, COUNT(*) FROM media_request_outbox GROUP BY status;
SELECT status, COUNT(*) FROM media_processing_job GROUP BY status;
SELECT processing_status, COUNT(*) FROM media_asset GROUP BY processing_status;
SELECT COUNT(*) FROM media_processing_job WHERE status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP;
```

结合固定错误码、Go 日志的 eventId/assetId/jobId 与队列积压定位，不导出令牌、租约散列或用户资料。隔离队列仅保存 SHA-256 和固定原因。健康检查不代表处理完成，还需读取 SQL 终态和实际对象字节。

完整隔离入口为 `python scripts/ci/media-runtime-smoke.py`，需要 Docker、测试 Python 依赖及 Playwright Chromium；会创建专属容器、卷和测试账号，执行停机/重启/浏览器操作，只清理自己的 Compose 项目。结果在 `outputs/m3-runtime-ci/`，实际执行的 JUnit 与清理结果均通过后才能记录运行验收通过。

对象协议测试使用 [Adobe S3Mock 5.2.3](https://github.com/adobe/S3Mock/tree/5.2.3)，不证明真实 AWS/R2 ACL、签名过期、生产持久性或 SLA。自动手机浏览器检查不替代真机、Safari 和系统软键盘专项。
