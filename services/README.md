# 独立服务

此目录包含 Go 实时接入与媒体处理组件。核心、网关、通知和可独立部署的聊天应用由根 Maven 工程构建。

## 聊天实时接入

[`chat-realtime/`](chat-realtime/) 管理 WebSocket 连接，核验会话，并消费 RabbitMQ 上的聊天变更信号。Java 聊天应用负责业务规则和消息持久化，客户端收到信号后通过 API 补拉。分库启动与恢复演练见 [独立聊天部署](../docs/engineering/local-development.md#独立聊天部署)，鉴权与协议见 [服务构建和通信契约](../docs/architecture/12-M7-service-build-and-contracts.md)。

从仓库根目录进入服务目录，执行 Go 检查。

```sh
cd services/chat-realtime
go vet ./...
go test -race -count=1 ./...
```

## 媒体 worker

Go worker 负责异步读取原图、生成预览并回传结果。媒体归属、任务、租约及最终结果由 Java 持久化；Go 不直接写论坛数据库，也不负责公开上传鉴权。RabbitMQ 确认与回传的语义见[媒体契约](../docs/engineering/m3-media-contract.md)。

源码按 `cmd/media-worker/` 入口和 `internal/` 实现分组；测试与对应 Go 包放在一起。构建配置、依赖锁和 Dockerfile 保留在服务根目录。

在 `services/media-service/` 执行：

```sh
gofmt -l .
go vet ./...
go test -race -count=1 -json ./...
```

Go 版本要求见 `go.mod`，CI 固定的具体版本见[工作流](../.github/workflows/ci.yml)。race 需要 CGO 和本机 C 编译器；本机与 CI 的工具版本分别记录。运行时 RabbitMQ、Java、存储、故障恢复及通知 SSE 由独立容器门禁验证，单元测试不能替代它们。

配置、健康检查和恢复操作见[服务手册](media-service/README.md)及[M3 运维](../docs/engineering/m3-operations.md)。新增服务前先明确数据所有者与契约，不因新增目录就宣称已经完成业务微服务拆分。
