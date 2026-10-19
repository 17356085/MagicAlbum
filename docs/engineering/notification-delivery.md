# 通知投递与运维

## 事务与模式

`app.notifications.client.mode=remote` 时，评论、点赞和关注与通知 Outbox 写入在同一个核心数据库事务中提交。偏好查询或 Outbox 插入失败会回滚主操作；远程 HTTP 故障不会使已经提交的主操作失败。`PostService.create` 与既有点赞/关注写入均有服务级事务。排队器要求调用方已开启事务。

mock 模式继续使用内存通知，供测试和临时开发使用，不提供重启后恢复能力。`app.notifications.client.enabled=false` 不生成通知。浏览器 GET/PATCH 使用用户 Bearer 凭证，内部 POST 使用独立服务凭证；Outbox 不改变用户隔离规则。

偏好关闭时不入队；投递前再次检查偏好，后来关闭的事件标为 SUPPRESSED。偏好检查与远程写入之间仍有时间窗口，不能保证发送过程中更改偏好立即撤销在途请求。

## 重试与去重

每个收件人的事件具有固定 UUID，记录在 `notification_outbox`。配送器先用短事务的条件更新获取一条事件的租约，提交后再发 HTTP；每条投递前单独 claim，避免批量事件排队耗尽租约。完成状态更新必须匹配租约 owner。进程崩溃后，其他配送器可接管过期租约。

通知服务的 `notifications.idempotency_key` 有唯一索引。同一个事件和负载的重试返回已有通知；相同键携带不同负载返回 409。唯一键插入在独立事务中完成，冲突重读使用新的数据库访问，避免失败事务污染。这是至少一次投递加收件箱去重，不是跨服务事务或通知必达保证。旧调用可以不提供 eventId，保留原有行为。

网络异常、408、429 和服务端错误指数退避；其他客户端错误直接进入 FAILED，达到重试上限也进入 FAILED。失败信息只存异常类别或 HTTP 状态，不能记录密钥、负载或任意下游错误文本。

配送器每次只在实际投递前获取一条租约。候选查询分别取得到期时间最早的 `PENDING` 和已过期 `IN_FLIGHT`，再按可执行时间（`PENDING.availableAt` 或过期租约的 `leaseUntil`）与行 ID 合并排序；因此新 pending 事件持续到达时，已过期事件仍会公平进入本批次，不会被新事件长期挤出。租约提交后才执行 HTTP，完成状态必须再次匹配 owner。

默认设置位于核心服务的 `notification-outbox.properties`，应用配置和环境可覆盖：每 2 秒检查一次，每次最多 50 条，租约 30 秒，最多 5 次，起始退避 2 秒、上限 1 天。成功和抑制事件保留 7 天；FAILED 保留供检查与人工重试。仅关闭 `app.notifications.outbox.scheduling-enabled` 可以暂停自动配送，排队和人工查询仍保留。

管理员接口使用既有持久化管理员身份判定：

- `GET /api/v1/admin/notification-outbox`：各状态数量。
- `GET /api/v1/admin/notification-outbox/{id}`：事件状态、尝试次数、时间及脱敏失败类别，不返回通知内容。
- `POST /api/v1/admin/notification-outbox/{id}/retry`：仅 FAILED 可重新入队，保留 UUID，重置尝试预算。
- `GET /api/v1/admin/notification-outbox/diagnostics`：各状态数量与最早待投递年龄。请求关联、日志脱敏和查看步骤见 [M1 可观测性手册](observability.md)。

## 超时与凭证

通知 client 默认连接 2 秒、读取 5 秒；`app.notifications.client.connect-timeout-ms/read-timeout-ms` 各允许 1–10000 毫秒。远端最坏情况是连接 10 秒加读取 10 秒，共 20 秒；核心服务对 `app.notifications.outbox.lease-duration` 强制至少 30 秒，为数据库提交和调度留下余量。生产配置应保持不低于 30 秒，若将来提高 HTTP 超时上限也必须同步提高租约。

Gateway 普通接口默认连接 2 秒、响应 8 秒，使用 `GATEWAY_CONNECT_TIMEOUT_MS` 和 `GATEWAY_RESPONSE_TIMEOUT` 覆盖。AI 路由先于普通核心路由匹配，响应预算为 120000 毫秒，保留流式场景。配置单位遵循 [Gateway 官方超时说明](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/http-timeouts-configuration.html)：全局连接为毫秒、响应为 Duration，路由元数据响应为毫秒。它不是整个 AI 会话的总时长限制。

两端的 `INTERNAL_SERVICE_TOKEN` 必须一致，从环境或本机未跟踪配置读取。要求 32–256 个无空格的可打印 ASCII 字符，拒绝明显占位及字符种类过少的值；应使用安全随机数生成器生成至少 32 个随机字节并做 Base64URL 编码。长度与字符数量只用于发现配置错误，不能证明实际随机熵。启动脚本已使用随机凭证。

轮换时生成新值，更新两个服务配置并协调重启；旧值不再有效。不要把值写入日志、提交或审查报告。当前不支持双密钥过渡，无中断轮换需要单独扩展协议。

## 数据迁移与启用顺序

先部署 notification-service 的 V3 幂等索引迁移，再部署 backend 的 V20261014 Outbox 表迁移和代码；否则旧通知服务可能忽略事件 ID，失去去重保障。两组迁移各自使用既有 Flyway 历史表。数据库名称和 JWT issuer 保持兼容。

修改源码不会自动改变正在运行的 JVM。本轮只在隔离测试数据库验证，不自动重启本机服务，也不手工执行运行中数据库迁移。启用前应使用备份后的目标 MySQL 复验 Flyway 和 Hibernate validate；H2 兼容模式不能替代该部署验收。

`scripts/Test-NotificationRuntime.ps1` 是独立的 PowerShell 7.4+ 验证入口：运行前先在仓库根目录执行 `mvn -B package`，再由脚本使用临时容器、临时端口和临时凭证验证迁移、停机重试、幂等重放及收件人隔离。脚本只清理本次运行登记的容器、进程和凭证文件。通过该脚本不代表完整 UI 或生产迁移已经验收。
