# 技术扩展：RabbitMQ 事件驱动与异步处理

## 目标
- 将耗时或非强一致逻辑异步化（邮件通知、索引构建、统计计数）。
- 解耦服务之间的强耦合调用，提升系统弹性。

## 拓扑与约定
- 交换机：`app.events`（topic）。
- 路由键：`threads.created`、`users.registered` 等。
- 队列：`threads.indexer.q`、`mail.sender.q`；
- 重试与死信：为每个队列配置 DLX 与延迟重试策略。

## Spring Boot 集成
- 依赖：`spring-boot-starter-amqp`。
- 配置示例：
```
spring.rabbitmq.host=rabbitmq
spring.rabbitmq.port=5672
spring.rabbitmq.username=user
spring.rabbitmq.password=pass
spring.rabbitmq.listener.simple.concurrency=2
spring.rabbitmq.listener.simple.max-concurrency=8
spring.rabbitmq.template.retry.enabled=true
spring.rabbitmq.template.retry.max-attempts=3
```
- 序列化：统一使用 JSON，携带 `messageId` 与事件版本；
- 幂等：基于 `messageId` 与数据库/outbox 记录去重；
- 失败处理：按业务配置重试、DLQ 与报警。

## 与项目场景
- 帖子创建事件：触发全文索引更新与推送通知；
- 用户注册事件：触发欢迎邮件与统计更新。

## 监控与运维
- 启用管理插件，监控队列消息堆积、消费者速率与连接数；
- 配置告警阈值与自动扩容消费者数（容器副本）。

## 风险与回滚
- 谨慎处理消息丢失与重复投递；
- 明确事件契约与版本演进，避免消费者崩溃。