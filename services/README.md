# 独立服务

当前此目录包含 [Go 媒体 worker](media-service/README.md)。Java 核心、Gateway 和通知服务仍是根 Maven 聚合模块，分别位于 `backend/`、`gateway/`、`notification-service/`。

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
