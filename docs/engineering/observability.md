# M1 可观测性手册

本页描述当前实现能提供的请求关联、错误与延迟查看方式，以及通知 Outbox 的管理员诊断入口。日志和接口都只用于排查，不代表一次 HTTP 成功就等于业务数据已经持久化或通知已经送达；需要结合数据库或通知服务的读回结果确认。

## 请求关联路径

浏览器发出的 `X-Request-Id` 先由 Gateway 校验：只接受长度不超过 128、由字母、数字、`.`、`_`、`:`、`-` 组成的值；缺失或不合法时由 Gateway 生成 UUID。Gateway 会把最终值转发给核心服务，并在响应中返回它。

核心服务和 notification-service 各自有独立的 `RequestCorrelationFilter`。过滤器把合法值放进当前线程的 MDC，并在请求结束时写出 `request_id`、HTTP 方法、匹配到的路由模板、状态和 `duration_ms`，最后清理 MDC。没有拿到 Spring MVC 路由模板时只写固定的 `route=unmatched`。过滤器不记录原始 URI、查询字符串、请求体或路径令牌，因此类似 S3 签名 URL 的令牌不会出现在这条请求日志中。

核心服务的 Outbox 入队从已校验的 MDC 值保存 nullable `request_id`。配送器从每一行的 request ID 设置本次投递 MDC；历史行缺少该值时使用合法的 event ID 作为稳定回退值。每次投递结束都会恢复进入投递前的 MDC 值。核心服务通过 `RemoteNotificationService` 发往 notification-service 的列表、已读和内部创建请求都会带上合法的 `X-Request-Id`，所以内部 HTTP 接收端可以用相同 ID 对照日志。request ID 只用于关联，不代替浏览器 Bearer JWT 或内部服务凭证；这两类凭证仍在各自的安全过滤器中校验。

日志级别格式在核心服务和 notification-service 的基础配置中包含：

```text
%5p [requestId=%X{requestId:-none}]
```

因此同一 MDC 上下文之外的业务日志会明确显示 `requestId=none`。请求日志中的显式 `request_id` 和日志级别前缀中的 MDC 值都应使用同一个合法 ID 交叉检索。

## 查看日志、错误和延迟

使用 `scripts/Start-Local.ps1` 的本地运行时，日志写入 `test-artifacts/local-runtime/backend.log`、`notification-service.log` 和 `gateway.log`。可以在 PowerShell 中按 ID 和状态查看：

```powershell
Select-String -Path test-artifacts/local-runtime/backend.log,
  test-artifacts/local-runtime/notification-service.log,
  test-artifacts/local-runtime/gateway.log -Pattern 'request_id=m1-request|requestId=m1-request'
Select-String -Path test-artifacts/local-runtime/*.log -Pattern 'status=5\d\d|duration_ms='
```

请求日志会保留 HTTP 状态和耗时。Cognito、全局异常处理、样例数据初始化和线程摘要发布日志只写目标、状态或异常类别等安全摘要；不写下游响应正文、异常 throwable、reason、密码、token 或查询内容。排查错误时先按 `request_id` 找到状态和耗时，再根据响应状态、Outbox 状态和数据库读回确认业务结果。

独立运行 `scripts/Test-NotificationRuntime.ps1` 时，脚本会把过程日志和验证结果放在它指定的运行目录；脚本中的 MySQL 查询会读回 Outbox 状态、尝试次数和 notification-service 的持久化记录。运行脚本前须按其说明准备构建产物和临时依赖。

## Outbox 管理员诊断

以下接口都要求已认证的管理员身份：

```text
GET  /api/v1/admin/notification-outbox
GET  /api/v1/admin/notification-outbox/diagnostics
GET  /api/v1/admin/notification-outbox/{id}
POST /api/v1/admin/notification-outbox/{id}/retry
```

`/diagnostics` 返回各状态的 `counts` 和 `oldestPendingAgeSeconds`。年龄按最早 `PENDING.created_at` 计算，当前没有待投递行时返回 `0`；数据库使用 `(status, created_at)` 索引支持这次诊断查询。`/{id}` 可继续查看单行状态、尝试次数、时间和脱敏失败类别；`retry` 只重新排队 FAILED 行并保留事件 UUID。接口不返回通知正文、token 或任意下游错误文本。

诊断接口本身是管理读回，不等于投递完成。要确认一次投递，需看到 Outbox 行从 `PENDING`/`IN_FLIGHT` 到 `DELIVERED` 的数据库状态，并在 notification-service 的通知表按事件幂等键读到唯一记录；异常时可结合 `FAILED`、尝试次数、状态日志和 `oldestPendingAgeSeconds` 判断积压。

## 现有性能指标

`application-perf.yml` 已为核心服务开启独立管理端口（默认 `9090`）和 `/actuator/prometheus`，并开启 HTTP server 请求直方图。使用 perf profile 启动后，通过管理端口读取该端点；当前配置没有新增 Outbox Prometheus 指标，也没有部署 Grafana。Outbox 的数量和最老待投递年龄以管理员诊断接口为准。配置存在或端点返回 HTTP 200 只能证明接口可访问，仍应读取实际指标和数据库状态。
