# 技术扩展：Spring Cloud 微服务化规划

## 目标
- 随业务增长，从单体逐步拆分为多服务，提升可扩展性与团队协作效率。

## 建议的初始架构
- API 网关：Spring Cloud Gateway（统一路由、鉴权与限流）。
- 服务发现：Consul 或 Nacos（推荐 2024.x 生态，兼容 Spring Boot 4）。
- 配置中心：Spring Cloud Config（或使用 Nacos 配置）。
- 可观测性：Micrometer + Prometheus + Grafana，分布式追踪（Zipkin/Tempo）。

## 服务拆分建议
- 用户服务（User Service）：账户、认证、资料。
- 内容服务（Threads Service）：帖子、分区、搜索索引。
- 文件服务（File Service）：上传与 CDN 加速。
- 网关服务（Gateway）：统一入口与安全策略。

## 通信与契约
- REST/OpenAPI 作为主要通信；
- 事件驱动：与 RabbitMQ 集成进行异步处理（如通知与索引）。

## 渐进迁移步骤
1. 引入 Gateway，保持后端 `end` 为后端服务；
2. 将用户模块独立为 `user-service` 并注册到发现组件；
3. 将帖子模块独立为 `threads-service`，前端经由网关路由；
4. 统一日志、指标与追踪，完善熔断与重试策略。

## 版本与兼容
- 选择 Spring Cloud 2024.x，与 Spring Boot 4 对齐；
- 明确各服务的依赖与启动顺序，避免循环依赖与端口冲突。

## 风险与回滚
- 配置一致性与网络稳定性是关键，优先在灰度环境验证；
- 通过网关开关与服务权重控制快速回滚到单体路径。