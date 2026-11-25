# M1 可观测性手册

本页描述当前实现能提供的请求关联、错误与延迟查看方式，以及通知 Outbox 的管理员诊断入口。日志和接口都只用于排查，不代表一次 HTTP 成功就等于业务数据已经持久化或通知已经送达；需要结合数据库或通知服务的读回结果确认。

## 请求关联路径

浏览器发出的 `X-Request-Id` 先由 Gateway 校验：只接受长度不超过 128、由字母、数字、`.`、`_`、`:`、`-` 组成的值；缺失或不合法时由 Gateway 生成 UUID。Gateway 会把最终值转发给核心服务，并在响应中返回它。

核心服务和 notification-service 各自有独立的 `RequestCorrelationFilter`。过滤器把合法值放进当前线程的 MDC，并在过滤器调用返回时写出 `request_id`、HTTP 方法、匹配到的路由模板、状态和 `duration_ms`，最后清理 MDC。没有拿到 Spring MVC 路由模板时只写固定的 `route=unmatched`。过滤器不记录原始 URI、查询字符串、请求体或路径令牌，因此类似 S3 签名 URL 的令牌不会出现在这条请求日志中。

`duration_ms` 衡量本次过滤器调用，不能当作 SSE 等异步流的完整会话耗时；流建立时的 200 也不证明后续流处理成功。MDC 不会自动跨线程传播，当前通知 Outbox 配送器已显式恢复关联值，其他异步任务需要另行传递和验证。

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

## 微服务联调指标

加入 `docker-compose.observability.yml` 后，核心通过私网管理端口提供待投递、投递中、失败记录数和最老待投递年龄。Go 在服务私网端口提供 broker 就绪状态、已认证连接数和收到的广播次数。Prometheus 通过 Docker DNS 分别采集 Go 节点。广播次数统计每个节点收到的变化事件，页面是否收到提示和补齐正文由双节点与浏览器验收确认。[Compose 配置](../../docker-compose.observability.yml) · [采集规则](../../perf/prometheus/microservices.yml)

```powershell
docker compose --env-file .env -f docker-compose.yml -f docker-compose.chat.yml -f docker-compose.observability.yml up -d --build --wait
docker compose --env-file .env -f docker-compose.yml -f docker-compose.chat.yml -f docker-compose.observability.yml exec -T prometheus promtool query instant http://127.0.0.1:9090 'up'
```

现有规则检查核心指标无法采集、实时节点中断、Outbox 积压年龄和失败记录。通过查询 `ALERTS` 可以在隔离环境查看触发及恢复。规则里的时间用于联调，生产阈值和通知渠道需按真实流量与值班安排配置。[告警规则](../../perf/prometheus/microservices-alerts.yml)

叠加搜索配置后，内容 Outbox 提供各投递状态的记录数，以及最早待投递记录自可投递时点起的年龄，使用独立于通知 Outbox 的指标和告警。搜索记录事件成功写入索引时的年龄，并累计失败转投请求、重放成功和隔离成功的次数。这些指标使用固定维度，事件内容和账号标识留在业务系统内。[搜索运维说明](m2-search-operations.md)
### 聊天实时提示指标

分库部署叠加 `docker-compose.chat-observability.yml` 后，Prometheus 可在 Compose 网络内采集聊天进程的管理端口。聊天提示指标 `bluealbum_chat_realtime_signal_dropped_total` 按 `reason` 标记原因，`queue_full` 表示异步队列已满，`rabbit_failure` 表示 RabbitMQ 发布异常。两类计数用于检查实时提示，浏览器从消息接口补拉已提交的历史。[分库监控配置](../../docker-compose.chat-observability.yml) · [Prometheus 采集](../../perf/prometheus/microservices.yml)

### OpenTelemetry 链路与 Loki 日志

在隔离部署中使用 `docker-compose.telemetry.yml` 启动 OpenTelemetry Collector、Tempo 和 Loki。它为网关、核心、独立聊天和通知的 Java 进程启用同一个只读 Java agent。原有 Prometheus 继续采集指标。采集器只接收内部 OTLP，Tempo 与 Loki 的查询端口仅绑定本机环回地址。[Compose 配置](../../docker-compose.telemetry.yml) · [Tempo 接收配置](https://grafana.com/docs/tempo/latest/set-up-for-tracing/instrument-send/set-up-collector/otel-collector/) · [Loki OTLP](https://grafana.com/docs/loki/latest/send-data/otel/)

从 OpenTelemetry Java agent 的发布页获取 `opentelemetry-javaagent.jar`，将 `BLUEALBUM_OTEL_AGENT_JAR` 设为该文件的绝对路径。保持隔离环境自己的 `.env` 和宿主端口，用以下命令运行和检查网关到核心的请求。验证要求 Tempo 记录跨进程父子 span，Loki 能检索到同一请求在网关和核心的日志。运行脚本的完整验收会登录公开 WebSocket，再从隔离的 Go 监听端口发起受跟踪的会话校验。发送真实私信后，验证 Java 经 RabbitMQ 到 Go 的消息提示。[Java agent 发布页](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.20.1) · [运行验证](../../scripts/ci/telemetry-runtime.py)

```bash
docker compose --env-file .env -f docker-compose.yml -f docker-compose.chat.yml \
  -f docker-compose.telemetry.yml up -d --build --wait
python scripts/ci/telemetry-runtime.py --gateway http://127.0.0.1:9000 \
  --tempo http://127.0.0.1:3200 --loki http://127.0.0.1:3100 \
  --output outputs/telemetry-runtime/checks.json
```

采集器删除认证头、Cookie、查询参数和数据库语句属性。当前配置使用本机文件存储和 24 小时保留期，应用日志仍需遵守现有敏感字段约定。Go 实时服务只在遥测配置启用时导出认证和消息提示的有限时长 span，沿途传播 W3C Trace Context。媒体遥测配置将上传请求的 W3C Trace Context 随媒体任务保存，Go worker 从 RabbitMQ 获取后延续到 Core 回调。两种 Go 服务继续保留原有指标。默认栈的导出保持关闭。[采集器配置](../../perf/telemetry/collector.yml)

2026-11-24 的隔离联调从 Tempo 读回网关到核心的父子关系及八个 span，并用同一请求 ID 从 Loki 读回网关和核心日志。报告为 `outputs/architecture-bundled-browser-20261124-v6/telemetry-runtime.json`。

媒体任务可叠加 `docker-compose.media-telemetry.yml`。该配置复用 Tempo、Loki 和 Collector，媒体任务继续使用独立的 RabbitMQ 队列。CI 用真实上传、异步处理和结果回调生成同一条链路，保存 `media-trace.json`。[媒体遥测配置](../../docker-compose.media-telemetry.yml) · [媒体验收](../../scripts/ci/media-runtime-smoke.py)
