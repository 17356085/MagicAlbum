# 工程文档索引

运行项目从[根 README](../../README.md)开始。实施状态以[阶段记录](stage-progress.md)为准；带日期的评审保留当时的提交、失败和验证范围，不能当作当前运行结果。

## 目录与开发规范

- [目录与文件归属](repository-layout.md)：源码、配置、测试、生成物和私有运行状态。
- [编码与验证规范](coding-standards.md)：前端、Java、脚本及变更验证。
- [本次结构与健康度审查](project-health-2026-10-22.md)：整理内容、风险分级和最终测试结果。
- [贡献约定](../../CONTRIBUTING.md)、[产品约定](../../PRODUCT.md)。
- [脚本索引](../../scripts/README.md)、[独立服务索引](../../services/README.md)、[Python 测试](../../tests/README.md)。

## 架构与契约

- [内部模块契约](module-contracts.md)：业务责任、数据归属与事务边界。
- [内容事件 v1](content-events-v1.md)：内容版本、Outbox 与搜索同步。
- [媒体处理契约](m3-media-contract.md)、[M3 运行时契约](m3-runtime-contract.md)：Java、RabbitMQ、Go 与结果回传。
- [版本化摘要任务契约](m4b-summary-task-contract.md)：主动生成、取消、权限和预算账本。
- [开发与技术演进路线图](development-roadmap.md)：已确认阶段和可选范围。

## 运行与恢复

- [M0 完整容器验收](m0-runtime-verification.md)：真实浏览器、重启、两库及上传恢复。
- [通知投递与运维](notification-delivery.md)：Outbox、幂等与通知数据库。
- [请求观测](observability.md)：请求关联、日志和故障排查入口。
- [搜索运维](m2-search-operations.md)、[MySQL 内容事件验收](m2-mysql-runtime.md)。
- [Go 媒体与 SSE 运维](m3-operations.md)。
- [手机号唯一性与迁移](phone-uniqueness.md)。
- [性能实验](../../perf/README.md)：隔离配置与实验边界，历史数字不是当前容量保证。

## 阶段实施与验收记录

| 阶段 | 记录 |
| --- | --- |
| M1 | [草稿与移动端](m1-drafts-and-mobile-2026-10-18.md)、[移动端调研](mobile-ux-research-2026-10-18.md)、[阶段交接](m1-handoff-2026-10-19.md) |
| M2 | [内容 Outbox](m2-content-outbox-2026-10-19.md)、[搜索投递](m2-search-delivery-2026-10-19.md) |
| M3 | [实施](m3-implementation-2026-10-19.md)、[媒体注册](m3-media-registration-2026-10-19.md) |
| M4 | [实施前检查](m4-preflight-2026-10-20.md)、[社区治理](m4a-governance-2026-10-20.md)、[摘要验收](m4b-summary-2026-10-20.md) |

## 历史评审

- [2026-10-17 工程评审](review-2026-10-17.md)。
- [2026-10-18 代码健康度](project-health-2026-10-18.md)、[复审与 M1 推进](review-and-m1-2026-10-18.md)。
- [身份与内容生命周期复审](final-auth-content-review-2026-10-20.md)。
- [整体复审与当前版本恢复](final-review-and-restore-2026-10-20.md)。

新增工程手册时放在本目录，并在相应分组添加链接。已有文档被代码、脚本或其他记录引用时，先核对调用方再迁移；不要为了分组移动全部历史文件。`docs/` 的其他本机资料、私有配置及 `outputs/` 证据不随克隆交付。
