# 网站接入 AI 开发方案文档

| 文档版本 | 修改日期 | 修改人 | 修改描述 |
| :--- | :--- | :--- | :--- |
| v1.0 | 2025-12-29 | AI Assistant | 初稿创建 |

---

## 1. 项目概述

### 1.1 业务目标与技术目标
**业务目标**：
1.  **提升信息获取效率**：通过 AI 自动生成帖子摘要，帮助用户在浏览列表或详情页时快速抓住核心内容，降低阅读成本。
2.  **增强用户互动与留存**：引入“客服小祥”AI 陪聊助手，提供 24/7 的即时响应、情感陪伴及常见问题解答，提升社区活跃度与用户粘性。

**技术目标**：
1.  **构建通用 AI 服务层**：在后端建立统一的 AI 服务接口，屏蔽底层模型（如 OpenAI、DeepSeek、Local LLM）的差异，实现模型的可插拔与热切换。
2.  **高性能与高可用**：确保 AI 功能在不影响主站性能的前提下运行，摘要生成响应时间控制在合理范围（异步处理），对话响应实现流式输出（<2s 首字延迟）。

### 1.2 项目范围与边界条件
**包含范围**：
- **帖子摘要**：
    - 对新发布的帖子自动触发摘要生成。
    - 支持手动刷新摘要。
    - 摘要存储与缓存机制。
- **AI 陪聊助手**：
    - 全局侧边栏聊天入口。
    - 支持上下文记忆的多轮对话。
    - 基于预设 Prompt 的角色扮演（客户小祥）。

**边界条件**：
- **输入限制**：仅处理文本内容，暂不支持图片、视频等多模态内容的理解。
- **长度限制**：帖子内容超过 Token 限制时进行截断处理。
- **语言支持**：主要优化中文场景。
- **合规性**：不处理涉及政治、色情等违规内容的生成（依赖底层模型风控及关键词过滤）。

### 1.3 需求规格
**功能需求**：
- [F1] 系统应能调用 LLM API 对指定文本生成 50-100 字的摘要。
- [F2] 摘要生成后应持久化存储到数据库，避免重复调用。
- [F3] 聊天助手需具备短期记忆能力（最近 10-20 轮对话）。
- [F4] 聊天助手需严格遵循“小祥版娘”的人设（活泼、热情、喜爱二次元）。

**非功能需求**：
- **响应性**：对话接口首字响应时间 < 2s。
- **可靠性**：外部 AI 服务不可用时，系统应平滑降级（如提示“小祥正在休息”），不引发系统崩溃。
- **安全性**：API Key 等敏感信息必须加密存储或通过环境变量注入，严禁硬编码。

---

## 2. 技术架构设计

### 2.1 系统架构图

```mermaid
graph TD
    User[用户终端 (Web/Mobile)]
    LB[负载均衡/网关]
    
    subgraph Frontend [前端 (Vue 3)]
        UI_Post[帖子详情页]
        UI_Chat[悬浮聊天组件]
        Store[状态管理 (Pinia)]
    end
    
    subgraph Backend [后端 (Spring Boot)]
        Controller[Web Controller Layer]
        Service[Service Layer]
        AIService[AI Service Module]
        DB[(MySQL Database)]
        Redis[(Redis Cache)]
    end
    
    subgraph External [外部服务]
        LLM[LLM Provider API]
        (DeepSeek / OpenAI)
    end

    User <--> LB
    LB <--> UI_Post
    LB <--> UI_Chat
    
    UI_Post -- REST (GET/POST) --> Controller
    UI_Chat -- SSE (Stream) --> Controller
    
    Controller --> Service
    Service --> AIService
    
    AIService -- Prompt --> LLM
    LLM -- Completion --> AIService
    
    Service <--> DB
    Service <--> Redis
```

### 2.2 API 接口规范
采用 RESTful 风格，部分接口使用 SSE (Server-Sent Events) 实现流式传输。

#### 2.2.1 生成/获取摘要
- **URL**: `POST /api/v1/ai/summary/{threadId}`
- **描述**: 触发或获取指定帖子的摘要。若摘要已存在且未过期，直接返回；否则异步触发生成。
- **Request**: 空（或包含 `forceRefresh=true`）
- **Response**:
  ```json
  {
    "success": true,
    "data": {
      "summary": "这是一篇关于...",
      "generatedAt": "2025-12-29T10:00:00Z",
      "status": "COMPLETED" // PENDING, COMPLETED, FAILED
    }
  }
  ```

#### 2.2.2 AI 对话 (流式)
- **URL**: `POST /api/v1/ai/chat/stream`
- **描述**: 发送用户消息，获取流式回复。
- **Header**: `Accept: text/event-stream`
- **Request**:
  ```json
  {
    "message": "你好，小祥！",
    "history": [ // 可选，携带最近几轮对话用于上下文
      {"role": "user", "content": "..."}
    ]
  }
  ```
- **Response (Event Stream)**:
  ```text
  data: {"content": "你好", "finishReason": null}
  data: {"content": "呀！", "finishReason": null}
  data: {"content": "", "finishReason": "stop"}
  ```

### 2.3 数据交换与存储
- **交互格式**: 统一使用 JSON。
- **数据库设计**:
    - `threads` 表新增字段 `summary` (TEXT), `summary_status` (VARCHAR)。
    - (可选) `chat_logs` 表用于审计，存储 `user_id`, `prompt`, `response`, `created_at`。

### 2.4 认证授权
- **用户认证**: 沿用现有的 JWT Token 机制，在 HTTP Header 中携带 `Authorization: Bearer <token>`。
- **服务认证**: 后端与 LLM Provider 之间通过 API Key 认证，Key 存储在服务器环境变量或配置中心，不对客户端暴露。

---

## 3. 开发实施计划

### 3.1 分阶段开发路线图

| 阶段 | 名称 | 目标 | 周期估算 |
| :--- | :--- | :--- | :--- |
| **Phase 0** | **POC (概念验证)** | 验证 Spring AI / HTTP Client 与 LLM 的连通性；确定 Prompt 效果。 | 2 天 |
| **Phase 1** | **Alpha (摘要功能)** | 完成后端摘要接口、数据库变更；前端帖子详情页展示摘要。 | 3 天 |
| **Phase 2** | **Beta (陪聊功能)** | 完成流式对话接口；前端侧边栏聊天组件开发；角色 Prompt 调优。 | 4 天 |
| **Phase 3** | **Production (生产)** | 异常处理（降级、重试）；敏感词过滤；性能优化；部署上线。 | 3 天 |

### 3.2 关键里程碑
- **M1**: 完成 AI Service 基础模块封装，跑通 Hello World 对话 (T+2)。
- **M2**: 帖子摘要功能上线内测，摘要准确率达标 (T+5)。
- **M3**: 小祥版娘组件集成，完成多轮对话测试 (T+9)。
- **M4**: 全功能验收通过，文档归档 (T+12)。

### 3.3 资源需求
- **人力**: 后端开发 1 人，前端开发 1 人（或全栈 1 人）。
- **算力/服务**: 
    - 外部 LLM API (如 DeepSeek-Chat, OpenAI GPT-3.5/4)。
    - 开发环境与生产环境服务器（需支持外网访问 API）。

---

## 4. 集成测试方案

### 4.1 端到端测试用例
| ID | 场景 | 前置条件 | 操作步骤 | 预期结果 |
| :--- | :--- | :--- | :--- | :--- |
| **TC-01** | 帖子摘要自动生成 | 新建帖子 | 1. 用户发布长文帖子。<br>2. 系统异步触发摘要任务。<br>3. 刷新详情页。 | 详情页显示“AI 摘要生成中”或显示已生成的摘要文本。 |
| **TC-02** | 陪聊对话流程 | 登录状态 | 1. 点击侧边栏聊天图标。<br>2. 发送“你是谁”。 | 收到流式回复，内容包含“我是小祥”等角色设定信息。 |
| **TC-03** | 摘要手动刷新 | 帖子已有摘要 | 1. 修改帖子内容。<br>2. 点击“刷新摘要”。 | 摘要内容根据新正文更新。 |

### 4.2 性能测试指标
- **摘要接口 (Sync)**: P95 < 500ms (仅返回状态或缓存)，实际生成过程异步化。
- **对话接口 (Stream)**: TTFT (Time to First Token) < 2s。
- **并发能力**: 支持 50 QPS (取决于 LLM 配额，需在网关层做限流)。

### 4.3 异常场景与验收
- **API 超时/不可用**: 前端应显示“服务繁忙，请稍后再试”，不白屏，不报错栈。
- **内容违规**: 若 LLM 返回拒绝生成，前端应友好提示“内容无法生成摘要”。
- **验收标准**: 摘要准确概括核心意图；对话流畅无明显逻辑错误；页面无视觉 Bug。

---

## 5. 部署运维方案

### 5.1 部署策略
- **容器化**: 应用通过 Docker 打包，配置中通过环境变量传入 `AI_API_KEY`, `AI_MODEL_NAME`。
- **配置隔离**: 生产环境与开发环境使用不同的 API Key 和配额策略。

### 5.2 监控与告警
- **监控指标**:
    - `ai.requests.count`: AI 接口调用次数。
    - `ai.requests.errors`: 调用失败次数。
    - `ai.latency`: 接口响应耗时。
- **告警规则**:
    - 错误率 > 5% 持续 5 分钟 -> 发送告警（邮件/钉钉）。
    - 响应时间 P95 > 5s -> 发送警告。

### 5.3 日志与审计
- 记录所有 AI 交互的关键元数据（不记录完整敏感内容，除非开启调试模式）：
  `[INFO] AI_CHAT | User: 1001 | Tokens: 150 | Duration: 1200ms | Status: OK`

---

## 7. 成本控制与经济性优化

在接入商业 AI API 时，必须平衡功能体验与运营成本。以下是针对本项目的详细经济性分析与优化策略。

### 7.1 成本分析 (Cost Analysis)

#### 7.1.1 定价模式与选型对比
以当前主流的两个高性价比模型为例（价格仅供参考，以官方实时定价为准）：

| 供应商 | 模型名称 | 输入价格 (Input) | 输出价格 (Output) | 特点 | 适用场景 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **DeepSeek** | **deepseek-chat (V3)** | ¥1.0 / 1M tokens | ¥2.0 / 1M tokens | 极高性价比，中文能力强，支持缓存 | **首选**：摘要生成、日常陪聊 |
| **OpenAI** | **gpt-4o-mini** | $0.15 / 1M tokens | $0.60 / 1M tokens | 逻辑推理稳定，响应快 | **备选**：复杂逻辑处理 |
| **OpenAI** | **gpt-4o** | $2.50 / 1M tokens | $10.00 / 1M tokens | 昂贵，能力顶尖 | **不推荐**：除非特定高价值场景 |

> **结论**：本项目优先采用 **DeepSeek-V3**，其成本仅为 GPT-4o-mini 的 1/5 左右，完全满足摘要与闲聊需求。

#### 7.1.2 预估月度支出
假设日活用户 (DAU) 500，日均新增帖子 50 篇，平均每人对话 10 轮。

1.  **帖子摘要 (Thread Summary)**:
    - 输入：平均 1000 汉字 (~1500 Tokens)
    - 输出：平均 100 汉字 (~150 Tokens)
    - 日消耗：50 * (1.5k * 1 + 0.15k * 2) / 1000 * ¥1 ≈ **¥0.1/天**
2.  **AI 陪聊 (Chat Companion)**:
    - 输入：平均上下文 2000 Tokens (含历史记录)
    - 输出：平均回复 200 Tokens
    - 日消耗：500人 * 10轮 * (2k * 1 + 0.2k * 2) / 1M * ¥1 ≈ **¥12/天**

**预估月总费用**：(0.1 + 12) * 30 ≈ **¥363/月**。
*注：若使用 GPT-4o，费用将飙升至 ¥10,000+，故选型至关重要。*

### 7.2 技术优化方案 (Technical Optimization)

#### 7.2.1 请求缓存机制 (Caching)
- **摘要缓存**：
    - **策略**：帖子内容未变更时，再次请求摘要直接读取数据库 `threads.summary` 字段，不调用 API。
    - **实现**：`ThreadService` 中检测 `content_md` 的 Hash 值，若 Hash 变动才触发重生成。
- **对话缓存 (Context Caching)**:
    - **策略**：对于DeepSeek等支持上下文缓存的模型，利用其 Context Caching 功能减少长历史记录的 Input Token 费用。

#### 7.2.2 批量与延迟处理 (Batch & Async)
- **非实时摘要**：
    - **实现**：发布帖子时不阻塞主线程，而是发送消息至 **RabbitMQ** (`threads.summary.queue`)。
    - **削峰**：设置消费者并发数为 2-5，避免突发流量导致 API 触发限流 (Rate Limit)。
    - **夜间处理**：对于历史旧帖子的摘要补全，安排在凌晨低峰期进行批量处理。

#### 7.2.3 动态上下文窗口
- **策略**：在陪聊中，不盲目发送所有历史记录。
- **算法**：
    - 仅保留最近 10 轮对话。
    - 或使用 "滑动窗口" + "关键信息摘要"（将久远的历史对话概括为一句话存入 System Prompt）。

### 7.3 架构建议 (Architectural Advice)

#### 7.3.1 多供应商抽象层
- **设计**：在 Spring Boot 中定义 `AiProvider` 接口，通过 `application.yml` 配置切换实现类。
- **目的**：当主供应商（如 DeepSeek）故障或涨价时，可无缝切换至备用供应商（如 SiliconFlow、OpenRouter 或 OpenAI）。

#### 7.3.2 免费额度与开源替代
- **薅羊毛策略**：开发阶段优先使用各家厂商提供的免费额度（通常注册送 50-100 元）。
- **自建模型 (Self-hosted)**:
    - 若未来规模扩大，可考虑在本地服务器部署 **Ollama + Qwen2.5-7B-Int4**。
    - **成本平衡点**：当 API 月费 > 单卡 GPU 租赁费 (约 ¥500-1000/月) 时，转为自建。

### 7.4 实施步骤 (Implementation Steps)

1.  **成本压力测试**：
    - 在测试环境模拟 100 并发对话，观察 Token 消耗速率与 API 响应延迟，验证预算模型准确性。
2.  **监控看板搭建**：
    - 使用 Grafana 绘制 "Token Usage Daily" 和 "Cost Daily" 图表。
    - 数据源：应用日志 -> ELK/Loki -> Grafana。
3.  **熔断策略 (Circuit Breaker)**:
    - **每日限额**：Redis 设置 Key `ai:cost:daily`，每请求累加预估金额。当达到阈值（如 ¥20）时，自动拒绝新请求并降级回复：“今日 AI 额度已耗尽，请明日再来”。
4.  **定期评估**：
    - 每月 1 号导出上月账单，分析 Top 10 高频用户与异常调用，调整限流策略。

---

## 6. 文档要求

### 6.1 交付清单
1.  **API 文档**: 基于 Swagger/OpenAPI 3.0 自动生成，包含新增的 `/api/v1/ai/*` 接口定义。
2.  **开发者指南**: 说明如何切换底层 LLM 模型，如何调整 Prompt 模板。
3.  **用户说明**: 在网站“帮助中心”添加关于 AI 摘要和 AI 助手的简要说明及隐私免责声明。

### 6.2 术语表
- **LLM**: Large Language Model，大语言模型。
- **SSE**: Server-Sent Events，服务器发送事件，用于流式数据传输。
- **Prompt**: 提示词，用于指导 AI 生成内容的文本指令。
- **Token**: AI 模型处理文本的基本单位。

---

> **可行性验证注记**: 
> 本方案基于 Spring AI (或 Spring Cloud OpenFeign) 与 Vue 3 组合，技术栈成熟。流式对话采用 SSE 标准协议，无需引入 WebSocket 即可满足单向流式需求，降低了实现复杂度与资源消耗，经评估完全可行。
