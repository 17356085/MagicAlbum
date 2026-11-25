# M8 Kafka 与通知数据库试点

Kafka 试点沿用内容事务写入的事件 ID 和版本。同一事件的 RabbitMQ 与 Kafka 投递状态分开，搜索写入各自的物理索引。通知服务同时支持 MySQL 和 PostgreSQL 的 Flyway V4 模式，通过停写后复制到空目标库切换。

| 审计项 | 验收要求 | 运行证据 |
| --- | --- | --- |
| ARCH-10 | Kafka 离线时 RabbitMQ 继续投递。Kafka 失败持续达到上限后进入可查询状态，管理员可在恢复后用原事件重放。恢复时积压结清，重复消息及旧版本通过消费组位点核验，索引内容保持权威版本 | 四轮真实组件隔离验收，核对两套索引，停止和恢复 Kafka，再从三分区最早保留位点重放并对账 |
| ARCH-11 | MySQL 到 PostgreSQL 再到新的 MySQL 实例，核对通知、游标和事件三张表。应用启动后从原游标续订 SSE，继续生成 ID 和游标，并验证已读及请求幂等 | 同一候选提交打包通知服务，在三个隔离库演练，并保存迁移摘要、应用读回、镜像配置和制品校验信息 |
| ARCH-17 | 全量替换涉及现有其他 RabbitMQ 通道和 Core、Chat 的数据库依赖。内容搜索和通知试点用于证明各自领域的切换流程 | 按领域保留试点。全量改造继续采用线上写入规模、消息保留和恢复目标、其余业务通道演练、核心数据库 SQL 与回滚验证作为决策依据 |

## Kafka 运行操作

`GET /api/v1/admin/content-events/kafka/diagnostics` 返回待投递数、在途数、失败数、最早待投递年龄和最多 50 条失败记录的编号、尝试次数、错误码。观测配置提供独立的 Kafka 积压与失败告警。恢复 Kafka 后，通过 `POST /api/v1/admin/content-events/kafka/{id}/replay` 重新投递一条终态失败记录。核对 Kafka 与 RabbitMQ 搜索索引一致后再切别名。重复调用同一重放地址时，若记录已离开失败态，接口返回 404。

搜索消费者会将无效信封保存到同一 Kafka 集群的 `<内容主题>.quarantine`，确认入库后提交原位点。隔离记录包含原位点、错误码、长度与 SHA-256，样本保留最多 8 KiB。原主题的保留期按复核时限设置。外部依赖暂时不可用时保留位点并重试。试点需预建隔离主题并配置写入权限，消费者的处理失败以及隔离次数由运维监测。

```bash
M2_CI_KAFKA_PILOT=1 bash scripts/ci/content-search-smoke.sh
```

[内容事件设计](07-消息事件与Outbox设计.md) · [Kafka 试点决策](decisions/ADR-001-内容搜索Kafka并行验证.md) · [Kafka 告警](../../perf/prometheus/microservices-alerts.yml)

## 通知数据库运行验收

```bash
mvn -B -pl notification-service -Ppostgresql -DskipTests package
python -m pip install PyMySQL 'psycopg[binary]'
python scripts/ci/notification-postgres-pilot.py \
  --jar notification-service/target/insync-notification-service-0.0.1-SNAPSHOT.jar \
  --java java --postgres-profile postgresql \
  --output outputs/m8-notification-ci/notification-postgres-pilot.json
```

试点在各次复制时停止通知写入进程。实际切换还需暂停生产者并排空待处理工作，使用空目标实例或库，随后核对写入与通知积压，再恢复流量。反向迁移使用第三个空库以保留旧实例和演练证据。确认恢复点及切换窗口后再按部署规模评估。

[数据库归属与迁移](03-数据库归属与迁移.md) · [通知数据库试点](decisions/ADR-002-通知服务PostgreSQL迁移验证.md) · [CI 配置](../../.github/workflows/ci.yml)

## 同版本运行结果

候选提交 `106da24af8b1bc04bd5e3283a0521847de4e0121` 的两项专项任务均通过。Kafka 恢复了 8 条积压写入，消费组追平三分区位点。四轮测试验证了始终保留 RabbitMQ 投递、达到配置的尝试次数后进入失败态、管理员重放、无效信封隔离和历史重放。切换与回退对账覆盖 11 个文档。

通知往返在 CI 通过 56 项检查。本机相同实现额外验证了传入的 Java 路径，共通过 57 项。反向切换后由 MySQL 生成后续通知 ID，SSE 从 PostgreSQL 的上一游标恢复，重复请求保持单条通知，已读状态及游标经重启复核。[同版本 CI 与专项产物](https://github.com/17356085/MagicAlbum/actions/runs/45452436581)
