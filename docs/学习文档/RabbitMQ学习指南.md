# RabbitMQ 学习指南

## 1. 核心概念

RabbitMQ 是一个开源的消息代理和队列服务器，用于通过普通协议在完全不同的应用之间共享数据。

### 1.1 基本组件
- **Producer (生产者)**: 发送消息的应用程序。
- **Consumer (消费者)**: 接收并处理消息的应用程序。
- **Queue (队列)**: 存储消息的缓冲区。消息在被消费者接收之前一直存储在队列中。
- **Exchange (交换机)**: 接收生产者发送的消息，并根据路由规则将消息推送到一个或多个队列。
- **Routing Key (路由键)**: 生产者发送消息时指定的键，用于交换机决定将消息路由到哪个队列。
- **Binding (绑定)**: 交换机和队列之间的连接关系。

### 1.2 交换机类型
- **Direct Exchange (直连交换机)**: 根据完全匹配的路由键将消息路由到队列。
- **Fanout Exchange (扇形交换机)**: 将消息广播到所有绑定到该交换机的队列，忽略路由键。
- **Topic Exchange (主题交换机)**: 根据模式匹配（通配符）的路由键将消息路由到队列。`*` 匹配一个单词，`#` 匹配零个或多个单词。
- **Headers Exchange (头交换机)**: 根据消息头属性进行路由，而非路由键。

## 2. 消息确认与持久化

### 2.1 消息确认 (Acknowledgements)
为了确保消息不丢失，RabbitMQ 支持消息确认机制。
- **Consumer Acknowledgement**: 消费者在处理完消息后发送确认给 RabbitMQ。只有收到确认后，RabbitMQ 才会删除该消息。
  - `basicAck`: 肯定确认。
  - `basicNack`: 否定确认，可以重新入队。
  - `basicReject`: 拒绝消息。
- **Publisher Confirms**: 生产者发送消息后，RabbitMQ 会异步发送确认给生产者，告知消息是否已到达交换机/队列。

### 2.2 持久化 (Persistence)
确保 RabbitMQ 重启后数据不丢失。
- **Exchange Durable**: 声明交换机时设置为持久化。
- **Queue Durable**: 声明队列时设置为持久化。
- **Message Persistent**: 发送消息时设置 `delivery_mode=2`。

## 3. 集群部署

### 3.1 普通集群
- 所有节点共享元数据（交换机、队列定义等），但消息内容只存储在创建该队列的节点上。
- 消费者连接到任意节点都可以消费，如果连接的节点没有数据，该节点会从存储数据的节点拉取。
- 缺点：如果存储消息的节点宕机，消息将不可用。

### 3.2 镜像队列 (Mirrored Queues)
- 在普通集群的基础上，将队列的消息镜像到其他节点。
- 配置策略 (`ha-mode`, `ha-params`) 来指定镜像的数量和位置。
- 优点：高可用，主节点宕机后，从节点自动升级为主节点。

## 4. 业务场景实现

### 4.1 发布/订阅 (Publish/Subscribe)
使用 `Fanout Exchange` 实现。
- 场景：用户注册后，同时发送邮件通知和短信通知。
- 实现：
  1. 创建一个 Fanout 交换机 `user.registered`.
  2. 创建两个队列 `email.queue` 和 `sms.queue`.
  3. 将两个队列绑定到 `user.registered` 交换机。
  4. 生产者发送消息到 `user.registered`.
  5. 邮件服务监听 `email.queue`，短信服务监听 `sms.queue`.

### 4.2 延时队列 (Delayed Queue)
RabbitMQ 原生不支持延时队列，可以通过以下方式实现：
1. **TTL (Time To Live) + DLX (Dead Letter Exchange)**:
   - 设置消息的过期时间 (TTL)。
   - 配置队列的死信交换机 (DLX)。
   - 当消息过期后，会被转发到死信交换机，再路由到死信队列。
   - 消费者监听死信队列，处理“延时”后的消息。
2. **rabbitmq_delayed_message_exchange 插件**:
   - 安装插件后，可以使用 `x-delayed-message` 类型的交换机。
   - 发送消息时在 Header 中指定延迟时间。

### 4.3 削峰填谷
- 场景：秒杀活动瞬间流量巨大。
- 实现：前端请求不直接落库，而是发送到 MQ。后端服务按照处理能力从 MQ 拉取请求进行处理。

## 5. 本项目应用指南
- **依赖**: 在 `pom.xml` 中添加 `spring-boot-starter-amqp`.
- **配置**: 在 `application.yml` 中配置 host, port, username, password.
- **开发**: 使用 `@RabbitListener` 注解监听队列，使用 `RabbitTemplate` 发送消息。
