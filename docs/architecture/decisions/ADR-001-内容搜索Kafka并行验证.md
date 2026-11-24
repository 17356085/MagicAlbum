# ADR-001 · 内容搜索的 Kafka 并行验证

## 背景

RabbitMQ 已承担主题事件投递、搜索索引更新和故障重放。需要用同一业务链路验证 Kafka 可否作为候选载体。主题搜索的数据可从内容库重建，适合在隔离索引里对比结果。

## 决策

使用原内容事件信封及 `eventId`，Kafka 分区键使用聚合类型与聚合 ID。启用试点时，业务事务为同一事件登记独立的 Kafka 投递状态，各载体分别确认。发布结果未知时以原事件重试。Kafka 消费在搜索投影成功后提交位点。索引写入按内容版本校验，支持重复投递和版本乱序。

两个载体分别写入配置明确的物理索引。先从权威内容建立基线，再比较两套索引。切换时暂停内容写入，以权威库逐条核对两个索引的版本、可见性和删除标记。两边达到同一业务状态后切换公共别名。以同样流程切回 RabbitMQ 索引。

## 影响与验收

试点测试分别验证同一事件、Kafka 离线期间 RabbitMQ 继续送达、拒绝切到落后的索引、恢复后追平，以及在新索引产生日常写入后回退。运行在隔离依赖中，生产采用由单独的评审决定。保留主题消费进度和索引对账证据。

[事件契约和试点路线](../07-消息事件与Outbox设计.md) · [运行验收入口](../../../scripts/ci/content-search-smoke.sh) · [Apache Kafka 单节点容器示例](https://github.com/apache/kafka/blob/trunk/docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml)
