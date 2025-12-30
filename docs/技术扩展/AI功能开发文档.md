# AI 功能开发文档

## 1. 项目概述 (Project Overview)

### 1.1 背景
随着大语言模型（LLM）技术的成熟，为了提升“MagicAlbum”论坛的用户体验，决定引入AI技术来实现内容的智能处理与个性化交互。

### 1.2 目标
*   **内容增强**：为长篇帖子自动生成摘要，帮助用户快速获取核心信息。
*   **交互升级**：引入具有特定人格（丰川祥子）的AI客服助手，提供即时问答与网站导航服务，增加社区活跃度。

### 1.3 范围
*   后端集成火山引擎（Volcengine）/ DeepSeek API。
*   利用 RabbitMQ 实现文章摘要的异步生成。
*   利用 Server-Sent Events (SSE) 实现流式对话体验。
*   前端 UI 深度定制（魔法使之夜风格悬浮球、角色扮演设定）。

---

## 2. 系统架构与技术选型 (System Architecture & Tech Stack)

### 2.1 架构图
```mermaid
graph TD
    User[用户 (Vue3 Frontend)]
    
    subgraph Frontend
        Chat[AiChat.vue (SSE Client)]
        Thread[ThreadDetail.vue]
    end
    
    subgraph Backend [Spring Boot Server]
        Controller[AiController]
        Service[AiService]
        Listener[ThreadSummaryListener]
        DB[(MySQL)]
    end
    
    subgraph Middleware
        Redis[(Redis Cache)]
        MQ[(RabbitMQ)]
    end
    
    subgraph External_AI [AI Provider]
        Volcengine[火山引擎 API]
    end

    User -->|浏览帖子| Thread
    User -->|发送消息| Chat
    Chat -->|SSE 流式请求| Controller
    Controller --> Service
    Thread -->|发布帖子| MQ
    MQ -->|异步消息| Listener
    Listener -->|调用生成| Service
    Service -->|HTTP Request| Volcengine
    Service -->|存储摘要| DB
    Service -->|读取/写入历史| Redis
```

### 2.2 技术栈
*   **后端 Framework**: Spring Boot 3.x
*   **AI SDK/Client**: Spring REST Client / OkHttp (用于调用 OpenAI 兼容接口)
*   **异步处理**: RabbitMQ (处理耗时的摘要生成任务)
*   **数据缓存**: Redis (用于缓存对话上下文 History)
*   **前端**: Vue 3 + Composition API + Tailwind CSS
*   **通信协议**: Server-Sent Events (SSE) for Chat; REST for standard Ops

### 2.3 数据流向
1.  **摘要生成**: 用户发帖 -> 写入DB -> 发送消息至MQ -> 监听器消费消息 -> 调用AI生成摘要 -> 更新DB。
2.  **对话交互**: 用户发送问题 -> 前端建立SSE连接 -> 后端组装Prompt (System + History + User) -> 流式返回AI响应 -> 前端增量渲染。

---

## 3. 功能模块详细设计 (Detailed Module Design)

### 3.1 智能摘要生成 (Smart Summary Generation)
*   **核心类**: `ThreadSummaryListener`, `AiService`
*   **触发机制**: 监听 RabbitMQ 的 `thread.summary.queue` 队列。
*   **Prompt 设计**:
    ```text
    请为以下论坛文章生成一个简短的摘要（200字以内），概括主要观点：
    [文章标题]
    [文章内容]
    ```
*   **容错处理**:
    *   API 调用失败重试机制。
    *   Mock 模式：当检测到 API Key 包含 "fake" 时，返回预设的静态摘要，避免测试消耗额度。

### 3.2 个性化AI客服 (Personalized AI Customer Service)
*   **角色设定**: 丰川祥子 (Togawa Sakiko) - 这里的设定基于《BanG Dream! It's MyGO!!!!!》及其衍生作品。
*   **System Prompt 关键点**:
    *   **身份**: 以前是CRYCHIC的键盘手，现在是Ave Mujica的键盘手/通过客服打工维持生计。
    *   **性格**: 高傲、自尊心强、但在工作中必须保持礼貌（会有露馅的时候）、对过去（春日影）敏感。
    *   **口癖**: "客服小祥"、"祝您生活愉快"（甚至有些阴阳怪气）、偶尔冒出 "わたくし" (本小姐)。
    *   **知识库**: 注入 "MagicAlbum" 网站的相关知识（论坛功能、板块介绍）。
*   **头像配置**: 使用特定 URL (`https://storage.moegirl.org.cn/...`)。

---

## 4. 接口定义与交互流程 (API Definitions)

### 4.1 聊天接口 (Chat API)
*   **Endpoint**: `GET /api/ai/chat/stream` (实际实现中使用 GET 或 POST 配合 `MediaType.TEXT_EVENT_STREAM_VALUE`)
*   **Parameters**:
    *   `message`: 用户发送的消息内容。
    *   `uid`: 用户ID (用于区分 Redis 中的上下文)。
*   **Response**: `Content-Type: text/event-stream`
    *   Data Chunk: `data: {"content": "..."}\n\n`
    *   End Signal: `data: [DONE]\n\n`

---

## 5. 前端实现与UI交互 (Frontend Implementation)

### 5.1 组件结构
*   `src/components/AiChat.vue`: 核心聊天组件。
    *   **悬浮球设计**: 采用《魔法使之夜》美术风格。
        *   **Day Mode**: 天蓝色/青色渐变，象征日常。
        *   **Night Mode**: 深靛色/紫色辉光，象征魔夜。
        *   **动画**: SVG 旋转光环与呼吸灯效果。
    *   **聊天窗口**: 支持 Markdown 渲染 (`markdown-it`)，流式打字机效果。
    *   **数据处理**: 使用 `fetch` API 读取 `ReadableStream`，并处理 JSON 分包截断问题 (Buffer mechanism)。

### 5.2 状态管理
*   对话历史仅在本次会话的前端临时存储，后端 Redis 可选持久化短期记忆。
*   Day/Night 模式切换响应系统主题配置。

---

## 6. 测试与验证计划 (Test & Verification Plan)

### 6.1 Mock 模式测试
*   **目的**: 在未购买 API Key 的情况下验证业务流程。
*   **方法**: 在 `AiService` 中判断 `apiKey.contains("fake")`。
*   **效果**:
    *   摘要生成：返回 "【测试模式】这是一个模拟的摘要..."。
    *   对话回复：返回 "【测试模式】我是客服小祥，有什么可以帮您？"。

### 6.2 真实环境测试
*   **工具**: `curl` (PowerShell需注意转义), Postman。
*   **测试用例**:
    *   **长文本摘要**: 提交一篇 > 2000字的文章，确认摘要在 5-10 秒内生成并写入数据库。
    *   **角色扮演一致性**: 询问 "你是谁？" 或 "演奏一首春日影"，检查回复是否符合“丰川祥子”的设定（如拒绝演奏、表现出动摇等）。
    *   **并发测试**: 同时打开多个 Tab 进行对话，确认 SSE 连接互不干扰。

---

## 7. 部署与运维指南 (Deployment & Ops Guide)

### 7.1 环境配置
在 `application.yml` 或环境变量中配置：
```yaml
ai:
  api-key: ${VOLC_API_KEY} # 生产环境注入
  base-url: https://ark.cn-beijing.volces.com/api/v3 # 注意不要带 /chat/completions 后缀
  model: doubao-1-5-pro-32k-250115
```

### 7.2 Docker 部署
确保 `docker-compose.yml` 中包含 Redis 和 RabbitMQ 服务，并且网络互通。

### 7.3 监控
*   监控 RabbitMQ 的 `thread.summary.queue` 积压情况。
*   监控 API 调用错误率 (500 Error)，常见原因包括 Token 过期或 Context 超长。

---

## 8. 未来规划与优化 (Future Planning)
*   **成本优化**: 引入 Token 桶算法限制单用户每日调用次数。
*   **多模型支持**: 增加 DeepSeek R1 (推理模型) 的切换选项，用于处理复杂的编程类问题。
*   **RAG (检索增强生成)**: 建立向量数据库，让客服小祥能真正搜索论坛内的帖子内容来回答用户问题。
