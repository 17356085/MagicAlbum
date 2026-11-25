# 同频 · InSync

同频是一套中文兴趣社区系统，围绕分区讨论、关注动态和即时聊天展开。读者可以在电脑和手机上参与讨论，管理员通过举报审核和审计维护社区。

仓库支持本地开发和隔离 Demo。已验收的演示版本为 [v0.1.0-demo.1](https://github.com/17356085/in-sync/tree/v0.1.0-demo.1)，复演步骤见 [Demo 演练手册](docs/engineering/demo-rehearsal.md)。

## 能体验什么

| 场景 | 功能 |
| --- | --- |
| 浏览与讨论 | 分区和标签筛选、搜索、排行、发帖、评论、点赞、收藏、草稿与浏览记录 |
| 社交与消息 | 关注动态、好友申请、私聊、群聊、站内通知 |
| 社区治理 | 举报审核、内容隐藏与恢复、版主权限和审计 |
| 媒体与摘要 | 图片上传、可选异步预览、帖子摘要。Demo 使用 Mock AI |

## 快速开始

### Windows 本地开发

准备 PowerShell 7、Git、Docker Desktop、JDK 21、Maven、Node.js 和 pnpm。前端依赖版本见 [package.json](frontend/package.json)。

```powershell
git clone https://github.com/17356085/in-sync.git InSync
cd InSync
cd frontend
pnpm install --frozen-lockfile
cd ..
./scripts/Start-Local.ps1
```

启动后打开 [http://127.0.0.1:5173](http://127.0.0.1:5173)。要运行接口冒烟，可以单独执行 `./scripts/Test-Local.ps1`，它会创建测试账号、帖子和评论。用 `./scripts/Stop-Local.ps1` 停止本机栈。

端口、日志、故障排查和现有数据库注意事项见 [本地开发手册](docs/engineering/local-development.md#powershell-本地开发)。

### Compose 与隔离 Demo

[完整 Compose](docs/engineering/local-development.md#完整-compose) 会在容器内构建和启动应用。两种开发启动方式共用默认端口，每次选一种运行。开发配置绑定本机地址。

[隔离 Demo](docs/engineering/demo-rehearsal.md) 有默认部署和独立聊天两种模式，使用单独的项目、数据卷和合成账号。手册包含交互演示、自动彩排及旧版 MySQL 备份演练。

## 项目结构

| 目录 | 职责 |
| --- | --- |
| [frontend](frontend/README.md) | Vue 3 社区界面 |
| [gateway](gateway/) · [backend](backend/) | API 网关、首页聚合、账号、内容、社交和治理 |
| [chat-domain](chat-domain/) · [chat-service](chat-service/) | 聊天业务及可独立运行的 Java 聊天服务 |
| [notification-service](notification-service/) | 站内通知、持久化和 SSE |
| [platform-web](platform-web/) | Java 共享 Web 组件 |
| [services](services/README.md) | Go WebSocket 实时接入和异步媒体 worker |
| [scripts](scripts/README.md) · [tests](tests/README.md) · [perf](perf/README.md) | 运行脚本、API 与浏览器验收、性能实验 |

基础运行依赖 MySQL、Redis 和 RabbitMQ。默认部署由 Java Core 承担聊天业务。分库模式使用独立聊天服务和 MySQL，Go 实时接入负责在线信号。Kafka 内容事件与通知库 PostgreSQL 的验证范围见 [数据与事件试点](docs/architecture/13-M8-data-and-event-pilots.md)。

## 开发与验收

- [贡献指南](CONTRIBUTING.md) 说明短期分支、PR、合并和发布规则。
- [架构总览](docs/architecture/01-总体架构设计.md) 说明服务边界、数据归属和运行拓扑。
- [工程文档索引](docs/engineering/README.md) 集中收录本地开发、Compose、管理员初始化、数据库迁移与恢复手册。
- [自动化测试](tests/README.md) 说明 Python API 与浏览器验证入口。[CI 配置](.github/workflows/ci.yml) 列出各模块检查和隔离验收。

实际运行证据以对应提交和测试报告为准。再分发或商用前请先确认授权。
