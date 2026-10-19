# M3 执行接口与实施边界

2026-10-19，M3 完成批次的接口冻结记录。实现及最终运行证据另列；本记录本身不表示能力已完成。

## 媒体消息

Direct exchange `media.jobs.v1`，routing key `process`，durable queue `media.process.v1`。重试 exchange `media.jobs.retry.v1`、key `retry`、queue `media.retry.v1`（10 秒 TTL，死信回主 exchange/key）；隔离 exchange `media.jobs.dlx.v1`、key `quarantined`、queue `media.quarantine.v1`。所有发布使用持久化、mandatory 和确认；隔离仅保存消息摘要和固定原因。

消息仅含 `schemaVersion:1,eventType:MEDIA_PROCESSING_REQUESTED,eventId,assetId,jobId,jobVersion,preset:preview,processorVersion:"1",occurredAt`。UUID 为规范小写形式，时间为 UTC ISO8601；正文不超过 8192 字节，不携带 URL、路径、凭据或租约。

## 内部 HTTP

前缀 `/internal/media/v1/jobs/{jobId}`；独立 `X-Internal-Service-Token`，环境变量 `MEDIA_INTERNAL_SERVICE_TOKEN`，按 32 个随机字节生成。普通用户 JWT 不授予内部接口权限。所有 JSON/二进制响应 `Cache-Control:no-store`。

- `POST /claim`：JSON 是完整消息 envelope。200 响应 `status`，终态 `SUCCEEDED/FAILED/CANCELLED` 可直接 ACK；`CLAIMED` 还含 `assetId,jobVersion,preset,processorVersion,leaseToken,leaseUntil,inputSha256,contentType,byteSize`。409 表示 BUSY，404 表示消息不匹配真实任务；不能将 BUSY ACK 丢弃。
- `POST /renew`：JSON `{jobVersion,leaseToken}`；200 `{leaseUntil}`。默认租约 120 秒，处理预算 60 秒，续租间隔 30 秒；旧/过期租约返回409。
- `GET /input?jobVersion=1`：`X-Media-Lease-Token` 头；原始图片字节，读取仅绑定服务持有的对象键。404/409 不返回对象内容。
- `POST /result`：multipart 字段 `file,jobVersion,inputSha256,outputSha256,processorVersion,width,height`，租约放 `X-Media-Lease-Token`。Java 校验实际字节、格式、尺寸、摘要和输入绑定；200 `{status:"SUCCEEDED"}`。同一租约和相同结果重传幂等；冲突/过期/取消409，不覆盖已接受结果。
- `POST /failure`：JSON `{jobVersion,leaseToken,errorCode}`；固定代码 `UNSUPPORTED_FORMAT,ANIMATED_IMAGE,MALFORMED_IMAGE,INVALID_METADATA,IMAGE_LIMITS,INPUT_TOO_LARGE,OUTPUT_TOO_LARGE,ENCODE,TIMEOUT,INPUT_MISMATCH`。200 `{status:"FAILED"}`；相同失败重传幂等，旧租约409。

临时网络/存储问题重投，不写永久失败；处理程序的永久输入错误先经 failure 接口持久化后 ACK。退出放弃的工作不写永久失败。所有请求设连接和响应超时，拒绝重定向，不将服务令牌写入 URL、消息或日志。

## 持久化和对象

领取在短事务中锁定 job，核对真实 outbox/event/asset/version/preset；执行尝试最多5次，持久化 SHA-256 租约散列及到期时间。过期租约允许新尝试；定时恢复器重新激活原请求事件，不能创建新的 eventId。发布失败也在数据库重试，避免 broker 不可用时丢失任务。

结果先通过短事务预留 candidate 元数据，再写入对象，最后通过新事务按当前租约接受。对象写入不包含在 SQL 事务中。输出位置由 Java 生成：`media/{assetId}/v{jobVersion}/{preset}/p{processorVersion}/{outputSha256}.{jpg|png}`，使用资源原 provider；不同结果不会覆盖原图或彼此。

只清理超过保留期、且不属于已接受输出或有效 candidate 的派生对象；清理与预留/接受在同一 job 锁上串行。原始上传不自动删除。遗留 UPLOADING 意图按原位置/摘要对账，确认完整写入后再建立任务；不确定结果保留记录。

公开 owner 状态增加可选 `previewUrl,errorCode`，原字段和原 URL 语义不变；原图在失败时仍可用。手机状态组件不静默替换 Markdown，不跨账号继续查询。


用户取消/重试使用普通 JWT：POST /api/v1/uploads/assets/{assetId}/cancel 或 /retry，均返回完整 MediaAssetStatus。取消不删除原图；重试仅允许 FAILED/CANCELLED，创建新 jobId/eventId 和递增版本，owner 状态读取最新版本。

MEDIA_PROCESSING_ENABLED 默认关闭，开启时还须启用登记并配置媒体令牌；独立登记开关可用于仅登记阶段。完整启用、回退和观测见 [运行手册](m3-operations.md)。
