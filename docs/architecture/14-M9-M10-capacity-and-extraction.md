# M9 和 M10 的 Feed 与服务提取评估

现有关注流读 Content 的正常主题、Social 的关注和 Identity 的资料，在 Core 内执行。分页顺序为创建时间与主题 ID 倒序。首页 BFF 复用关注流接口，主题搜索由 Elasticsearch 投影提供，主题热门列表由回复与互动数据生成。[关注流查询](../../backend/src/main/java/com/example/demo/threads/repo/ThreadRepository.java) · [首页聚合职责](decisions/ADR-004-首页聚合与缓存边界.md) · [搜索运行说明](../engineering/m2-search-operations.md)

## Feed 评估方法

隔离演练使用实际 Flyway 迁移、MySQL 8 和当前 Spring Data 查询，分别记录系统现有投影及总数的耗时，以及等价过滤条件的分页、计数耗时和执行计划。固定数据下核验同一创建时间的顺序，并用上一页最后一项作为游标，在同一个深页比对有序 ID。插入新主题后验证页码偏移能否造成重复，再对照游标行为。隐藏、删除和取消关注分别做数据库读回。

报告记录合成数据规模和数据库镜像。仓储时间包含查询结果投影和计数，原型记录已有游标时的页面 SQL。每种场景预热两次并测量八次，记录分位数用于复验。该演练采用串行读、热缓存及合成内容，回复和点赞表没有负载，无法据此推算生产容量或峰值服务量。

```bash
python scripts/ci/feed-query-cost.py
```

[运行脚本](../../scripts/ci/feed-query-cost.py) · [工作流](../../.github/workflows/ci.yml)

## 服务提取与基础设施评审

| 审计项 | 决策门槛 | 下一次评审需要的证据 |
| --- | --- | --- |
| ARCH-15 Feed | 深页成本、关注扇出或数据库争用影响实际产品目标时，才为 Feed 增加独立存储和异步同步 | 实际流量的查询时延和数据库争用，独立投影的写入放大、回填时间及权限更新时延 |
| ARCH-16 Social | 服务发布可以独立，且关注、好友、拉黑与联系授权能够通过明确的所有者契约提供 | 分离后 Feed 查询能维持现有可见性，联系授权维持现有并发语义，端到端对比能显示部署或规模收益 |
| ARCH-16 Search | 搜索资源或恢复时间有独立扩缩容的需要 | Elasticsearch 投影、搜索回源和写入负载的分段指标，切换和回退对账 |
| ARCH-16 Content | 权威写入需要独立发布或扩容，且治理、通知和搜索消费者能承受独立故障 | 真实写入峰值、连接池占用和恢复时间，对应事务与 Outbox 证据 |
| ARCH-18 Kubernetes 和 Service Mesh | 运维环境确实需要多主机调度或统一服务间治理 | 跨主机恢复、滚动升级和故障复现，现有 Compose 基线的成本对照 |
| ARCH-18 Rust | 可复现热点和 Go、Java 基线显示需要独立组件 | 相同吞吐、延迟和资源指标，加上维护成本和回滚验收 |

当前保留 Core、网关、通知、Go 实时接入、Go 媒体 worker，以及可单独构建的聊天应用。ARCH-16 本轮暂缓提取 Social、Search 和 Content。Social 的关系状态与 Feed 仍共享 Core 事务和数据，搜索索引已有独立的 Elasticsearch 投影与回放入口，Content 仍负责帖子及治理更新并通过 Outbox 发布。本轮证据覆盖了这些边界的运行与恢复，未提供额外拆分可以降低业务负载或发布成本的实测。

ARCH-18 暂缓 Kubernetes、Service Mesh 和 Rust。现有 Compose 已覆盖两种聊天拓扑，并演练数据库迁移、回滚与恢复。Java 和 Go 的负载实验覆盖 WebSocket 鉴权，未发现需要替换语言的已定位热点，也未测出多主机调度或统一代理治理的需求。保留按上表重新评审的验收门槛。[总体架构](01-总体架构设计.md) · [聊天构建与契约](12-M7-service-build-and-contracts.md) · [Kafka 与数据库验收](13-M8-data-and-event-pilots.md)
