# M7 聊天构建、通信契约与运行验证

在 M6 已验收版本上，默认拓扑仍由 Core 执行聊天业务。分库拓扑使用独立打包的 `chat-service`，它通过已有内部契约向 Core 查询账号、会话和联系授权。[M6 验收](https://github.com/17356085/MagicAlbum/actions/runs/45396326553)

| 审计项 | 实现 | 验收 |
| --- | --- | --- |
| ARCH-08 | `chat-service` 拥有自己的 Maven 应用、Docker 镜像、配置与聊天专库迁移。两种拓扑共同使用 `chat-domain`，少量 Web 及限流组件由 `platform-web` 提供 | 可仅打包聊天应用及其依赖。模块守卫按源码归属拒绝直接引用 Core。验收默认部署、分库部署、迁移回滚和恢复 |
| ARCH-12 | 会话验证沿用 Protobuf v1。添加字段时保持现有字段编号和服务身份校验。启用 gRPC 后使用独立凭证，TLS 验证证书身份，超时后当前连接失败关闭 | Java 和 Go 的测试覆盖未知字段、错误身份和调用截止时间。隔离部署通过真实 Go 客户端校验 Java 会话 |
| ARCH-13 | 遥测配置启用 Go 导出。WebSocket 仅跟踪认证，Java 聊天提交后的 RabbitMQ 信号沿用发布链路，Go 消费后结束处理 span | 从 Tempo 读取网关和 Java 的 HTTP 链路、Go 和 Java 的会话验证链路，以及 Java 经 RabbitMQ 到 Go 的提示链路。从 Loki 比对同一 HTTP 请求的两端日志 |
| ARCH-14 | 首页 BFF 组合公共主题和分区、当前账号资料、关注流和未读数。公共片段可在短时间内复用，认证响应标记为 `private, no-store` | 网关 HTTP 测试复用公共缓存，验证两个账号与匿名访问相互隔离，并演练通知超时和恢复。独立部署核验同一网关上的三类访问 |

[分库 Compose](../../docker-compose.chat.yml) · [聊天应用](../../chat-service/pom.xml) · [Java 依赖规则](../../scripts/Check-JavaArchitecture.mjs) · [BFF HTTP 验收](../../gateway/src/test/java/com/example/gateway/HomeOverviewAuthorizationEndpointTest.java) · [遥测配置](../../docker-compose.telemetry.yml)

## 构建和部署

在仓库根目录分别执行下列命令。第一条只构建聊天应用与两个共享库。第二条生成独立聊天镜像。

```bash
mvn -B -pl chat-service -am package
docker build --file chat-service/Dockerfile --tag bluealbum-chat-service:0.1.0 .
```

分库栈可将 `BLUEALBUM_CHAT_SERVICE_IMAGE` 指向预先准备的聊天镜像标签或摘要。更新聊天时，在快照及迁移兼容性核验后，使用 Compose 的 `up -d --no-build --no-deps chat-service` 只重建聊天进程的容器。回滚镜像之前核对该版本与聊天库的 Flyway 迁移兼容性。数据库所有权变更继续按分库切流流程验证。[分库迁移与恢复](03-数据库归属与迁移.md) · [聊天 Dockerfile](../../chat-service/Dockerfile)

## 同负载对比

CI 在同一个 Java Core、RabbitMQ 和合成数据环境中，启动使用同一镜像的 HTTP/JSON 与 TLS gRPC Go 节点。两端关闭导出，以相同账号与并发数预热，再以 AB、BA 顺序执行等量 WebSocket 握手。统计连接到收到 `ready` 的吞吐和 p50、p95 延迟，并记录 Go 容器自身的 CPU 用量增量及内存。该比较包含握手及当前部署下的传输成本，资源指标仅覆盖 Go 容器。输出 `chat-auth-load.json`，每轮各保留结果。[负载脚本](../../scripts/ci/chat-auth-load.py) · [CI 验收](../../.github/workflows/ci.yml)
