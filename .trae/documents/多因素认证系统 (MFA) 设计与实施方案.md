# 多因素认证系统（MFA）实施计划

## 1. 方案设计文档
- **任务**：编写详细设计文档并存入 `docs/实施与配置/账户安全-多因素认证系统设计方案.md`。
- **内容**：
  - 认证流程图（登录、密码修改、手机/邮箱变更）。
  - API 接口定义（发送验证码、验证、执行变更）。
  - 数据库与 Redis 存储结构设计。
  - 安全策略说明（防刷、加密、审计）。

## 2. 后端基础设施建设
- **依赖管理**：在 `pom.xml` 中添加 `spring-boot-starter-data-redis` 和 `spring-boot-starter-mail`。
- **配置更新**：在 `application.yml` 中添加 Redis 连接配置和 JavaMailSender 配置（含默认值/占位符）。
- **通用服务**：
  - `CaptchaService`：基于 Redis 实现验证码生成、存储（加密）、校验、防刷限制。
  - `NotificationService`：集成邮件发送与 SMS 发送（初期可使用 Mock 或控制台输出）。
  - `AuditLogAspect`：AOP 切面记录敏感操作日志。

## 3. 核心业务流程实现
- **登录流程升级**：
  - 修改 `AuthService.login` 支持 "手机号+验证码" 和 "邮箱+验证码" 模式。
  - 更新 `LoginRequest` DTO 结构。
- **安全验证接口**：
  - 新增 `VerificationController`：`/api/v1/verify/send-code`。
- **账号变更流程**：
  - 新增 `AccountSecurityController`：
    - `/api/v1/account/security/change-password`
    - `/api/v1/account/security/change-phone` (分两步：验证旧号 -> 绑定新号)
    - `/api/v1/account/security/change-email` (分两步：验证旧箱 -> 激活新箱)

## 4. 前端功能开发
- **登录页改造**：增加“验证码登录”选项卡，实现获取验证码倒计时交互。
- **安全中心页面**：在“个人设置”中新增“账号安全”模块。
- **变更交互**：实现分步向导（Step Wizard）用于手机/邮箱修改流程。

## 5. 验证与测试
- **单元测试**：测试验证码生成、过期、防刷逻辑。
- **集成测试**：模拟完整登录与变更流程。
