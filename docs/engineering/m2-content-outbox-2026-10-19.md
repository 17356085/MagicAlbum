# M2.1 内容事件持久化验证（2026-10-19）

本批次为 `threads` 增加非 JPA `@Version` 的 `content_version`，旧数据和 Hibernate 测试建表默认从 0 开始。帖子创建、作者编辑（正文、标题、标签或分区）、审核隐藏和作者软删除都在原有事务中推进版本并写入 `content_event_outbox`；事件服务使用 `Propagation.MANDATORY`，生产 `ThreadService` 构造器直接依赖真实 recorder。重复删除先完成作者校验，再返回且不产生新事件。

事件行只包含固定 UUID、协议版本、THREAD 标识、帖子 ID、内容版本、事件类型、发生时间、可选合法 request ID 和 PENDING 状态。表不建立帖子外键，使用 `event_id` 与 `(aggregate_id, aggregate_version)` 唯一约束，并保留 `(status, id)` 索引。当前没有发布器、租约、RabbitMQ 或搜索改动。

定向验证使用 Maven 3.9.11、Java 21、`MAVEN_OPTS/JAVA_TOOL_OPTIONS=-Xmx512m -XX:ActiveProcessorCount=2`：

- `mvn -pl backend -DskipTests compile`：通过，Checkstyle 0 violations。
- `mvn -pl backend -Dtest=ContentEventOutboxMigrationTest test`：1 tests，Failures 0，Errors 0，Skipped 0。
- `mvn -pl backend -Dtest=ThreadContentEventIntegrationTest test`：6 tests，Failures 0，Errors 0，Skipped 0。真实 Spring 代理覆盖创建、标签/分区编辑、隐藏、删除、调用方回滚、真实唯一约束冲突回滚、越权/隐藏/重复删除、并发版本 1/2、MANDATORY 和非法 request ID。
- `mvn -pl backend '-Dtest=ThreadServiceTest,ThreadServiceCacheTest' test`：23 tests，Failures 0，Errors 0，Skipped 0。

本轮合并定向运行的 Maven、Surefire XML/TXT、架构、仓库和 diff 检查日志保存在 `outputs/m2-1-20261019/`。

迁移测试只在 H2 的 MySQL 兼容模式执行，证明已有行收到版本 0、事件唯一约束和读取索引生效；本批次没有连接或执行真实 MySQL，H2 结果不能作为 MySQL 目标实例的部署或兼容性证明。全 reactor/CI 也不替代目标 MySQL/Flyway 运行验证。当前尚未实现事件发布、租约和消费者。

## 主代理复核

主代理检查了生产实现、迁移、构造器调用及测试差异；验收覆盖真实数据库约束异常、403/404、越权删除、合法 request ID、并发等待超时和事件元数据不可变性。最终完整 Java reactor 为核心服务 287、网关 6、通知服务 25，共 **318 项通过**，0 失败、0 错误、0 跳过。证据为同目录的 `java-reactor.log`；架构与仓库检查通过，跨域历史白名单没有增加。

上述 318 项是本地首批代码的验证快照。远端 `0a8b90d` 是 M1 浏览器用例修正，其五项 CI 已通过，运行时 API 14、Selenium 1、Playwright 1 无失败或跳过；该 CI 不包含本批代码。

## MySQL 验收门禁

本轮增加[独立 MySQL 验收入口](m2-mysql-runtime.md)和 CI 任务。它先将合法旧库迁移至 `20261018.1` 并写入旧帖子，再升级到 `20261018.2`，检查旧行版本 0、唯一约束和索引；另一个独立库通过 Flyway 建表与 Hibernate `validate`，运行六个真实 `ThreadService` 事务场景。最后单独执行一遍完整写入链路，由 MySQL 客户端回读版本 1–5 的事件和版本 5 的删除状态。七个用例加一次确定性复跑共八次执行，不能描述为八个独立场景。

宿主机 Docker 临时 socket 已核对并改名备份；重启后引擎仍未就绪，且内存提交余量不足以安全叠加数据库和测试，因此本机没有完成此入口。CI 使用同一脚本、一次性 MySQL 8 容器、随机凭据及两个新库，不复用本地数据。脚本限制资源并核对清理归属，运行报告包含数据库读回和清理结果。

提交此入口时真实 MySQL 结果仍待远端执行；不能将入口存在、H2 通过或 M1 的绿色 CI 记为 M2.1 目标数据库验收。工程检查器本轮 21 项通过，仓库和 Java 边界检查通过。M2 整体未完成；目标数据库验收成功后，再推进 M2.2 的发布确认、租约、重放、乱序和投影。

后续结果：提交 [`5a22ed5`](https://github.com/17356085/MagicAlbum/commit/5a22ed53307e711c3dadd2270d5196cc4b896430) 的[六项 CI](https://github.com/17356085/MagicAlbum/actions/runs/39192940711) 全部成功。主代理已下载并读取 MySQL 8.0.46 的 JUnit、`readback.json` 和 `checks.json`：旧库升级、六项事务场景与一次确定性复跑通过，两个库迁移到 `20261018.2`，帖子版本 5/DELETED、五条按版本递增的 PENDING 事件及真实标签持久化正确；容器、匿名卷和凭据清理均通过。本地证据在 `outputs/m2-mysql-20261019/remote/`。这完成 M2.1 的目标数据库验收，不代表后续发布和搜索已完成。
