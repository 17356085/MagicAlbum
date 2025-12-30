# AI 功能接入实施计划

鉴于您已准备好 Redis 和 MQ 环境，我们将按照以下步骤正式开始开发。本计划将把基础设施集成到项目中，并完成前后端核心功能。

## 1. 基础设施与依赖准备
- **Docker 环境集成**:
  - 更新 `docker-compose.yml`: 添加 Redis (redis:alpine) 和 RabbitMQ (rabbitmq:management) 服务配置，确保开发环境一键启动。
- **后端依赖 (Maven)**:
  - 更新 `end/pom.xml`: 添加 `spring-boot-starter-data-redis` 和 `spring-boot-starter-amqp` 依赖。

## 2. 数据库与实体变更
- **Flyway 迁移**:
  - 创建 `V20251231_1__add_thread_summary.sql`: 为 `threads` 表添加 `summary` (TEXT) 和 `summary_status` (VARCHAR) 字段。
- **实体类更新**:
  - 修改 `Thread.java`: 映射新增字段。

## 3. 后端开发 (Spring Boot)
- **配置管理**:
  - 修改 `application.yml`: 添加 Redis、RabbitMQ 连接配置及 AI 服务配置 (DeepSeek API Key/URL)。
- **AI 服务层 (`AiService`)**:
  - 使用 `RestClient` 封装 DeepSeek API 调用，实现 `generateSummary` (摘要) 和 `chatStream` (流式对话)。
- **异步处理 (RabbitMQ)**:
  - **配置**: 创建 `RabbitConfig` 定义队列 `thread.summary.queue`。
  - **生产者**: 在 `ThreadService.createThread` 后发送消息。
  - **消费者**: 创建 `ThreadSummaryListener` 监听消息，调用 AI 生成摘要并更新数据库。
- **API 接口 (`AiController`)**:
  - `POST /api/v1/ai/summary/{id}`: 手动刷新摘要。
  - `POST /api/v1/ai/chat/stream`: SSE 流式对话接口。

## 4. 前端开发 (Vue 3)
- **API 封装**:
  - 创建 `front/src/api/ai.js`。
- **帖子摘要功能**:
  - 修改 `front/src/pages/ThreadDetail.vue`: 增加摘要展示区域和刷新按钮。
- **AI 陪聊功能**:
  - 新建 `front/src/components/AiChat.vue`: 实现悬浮聊天窗口与流式响应解析。
  - 修改 `front/src/components/SidebarRight.vue`: 添加聊天入口。

## 5. 验证与测试
- 启动所有 Docker 容器。
- 验证 API 连通性与消息队列处理流程。
