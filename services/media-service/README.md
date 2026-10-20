# Go media worker

这是 M3 异步图片处理进程，模块路径为
`github.com/17356085/MagicAlbum/services/media-service`。Java 拥有资源权限、任务版本、租约和结果；Go 不连接业务数据库，也不持有对象存储凭据。完整配置见 [M3 运行手册](../../docs/engineering/m3-operations.md)。图片内核仍可独立调用：

```go
result, err := processor.Transform(ctx, input, processor.PresetThumbnail)
```

`Transform` 只接受静态 JPEG 和静态 PNG。它先读取并检查输入，再执行
`DecodeConfig` 尺寸校验、完整解码、EXIF/eXIf 方向处理、双线性缩放和重新
编码。JPEG 输出使用质量 82；PNG 输出保留透明通道。重新编码不复制 EXIF、
GPS、文本或其他来源元数据。输出包含 bytes、MIME、实际宽高、SHA-256、预设
和固定处理器版本 1。

当前固定边界如下：

- 输入编码数据不超过 10 MiB；输出不超过 10 MiB。
- 解码前单边不超过 20,000 像素，总像素不超过 24,000,000。
- `thumbnail` 在 480×480 盒内缩小，`preview` 在 1600×1600 盒内缩小；小图不放大。
- 只支持 `thumbnail` 和 `preview`；GIF、WebP、SVG、APNG 以及坏图会被拒绝。
- JPEG EXIF 和 PNG `eXIf` 方向值只接受 TIFF 方向 1–8；损坏 EXIF 或非法方向会被拒绝。
- 预设、输入读取、格式、动画、元数据、图片尺寸、编码和取消等错误返回固定类别，错误文本不包含原始正文、路径或凭据。

处理过程在读取、像素行循环和输出写入处协作检查 `context.Context`。Go 标准库
的 JPEG/PNG 解码器本身不是可硬实时抢占的；取消请求会在解码返回以及内核的
下一个检查点得到处理。

进程入口 `go run ./cmd/media-worker`，必需变量为 `MEDIA_CORE_URL`、`MEDIA_AMQP_URL`、`MEDIA_INTERNAL_SERVICE_TOKEN`；`MEDIA_HEALTH_ADDR` 默认 `:9091`。`/live` 表示进程存活，`/ready` 表示已建立消息消费会话，`--self-health` 用于容器检查。默认单消费者、prefetch 1、处理预算 60 秒；依据实际租约剩余时间约三分之一动态续租，间隔不超过 30 秒，覆盖结果回传。HTTP 连接/响应限制 5/15 秒并拒绝重定向，发布确认最多等待 5 秒。

结果经 Java 接受后才 ACK；持久化延迟重试先确认再 ACK 原消息。非法消息只保留摘要和固定原因。连接中断后重建通道与拓扑，退出取消未完成执行并重投。结果响应丢失通过重新领取读取持久化终态解决。当前队列只启用 preview，thumbnail 是内核能力。Docker 构建固定 Go 1.26.9，使用非 root 运行时，Compose 限制 640 MiB；这些不等于容量压测或跨版本编码一致性承诺。

Core 在任一领取、输入读取、续租、结果提交或失败提交调用返回 401/403 时，worker 将认证配置视为暂停状态：当前消息和待处理消息都会重新入队，关闭当前消费会话并停止 readiness；不会写业务失败、隔离消息、发布延迟重试或自动重连。已经被结果提交确认的成功终态具有优先级，续租并发认证失败不会撤销该成功消息的 ACK。进程保持存活并继续提供 `/live`，修正配置后必须重启 worker 才能恢复消费。暂停日志只使用固定 `core_auth_paused` 类别，不包含凭据或消息正文。

验证：

以下命令从 `services/media-service` 目录执行。本机 Go 1.26.2 仅用于本地
原型验证；固定构建版本和跨 Go 版本的编码字节一致性仍由后续构建环境确定。

```powershell
$env:GOTOOLCHAIN = 'local'
$env:GOMAXPROCS = '2'
$env:GOMEMLIMIT = '512MiB'
$env:GOCACHE = 'D:\Desktop\BlueAlbum\outputs\m3-20261019\go-cache'
$env:GOMODCACHE = 'D:\Desktop\BlueAlbum\outputs\m3-20261019\go-mod-cache'
go test ./...
go vet ./...
go test -race ./...
```

Windows 的 race 验证需要可用的 C 编译器。本机使用进程级 GCC PATH 完成，
没有修改全局环境。M3.1 历史验证为 12 项主测试和 14 个子场景通过，
gofmt、vet、race 通过；证据位于仓库 `outputs/m3-20261019/final/`。
Java–Go 集成约定见 [M3 契约草案](../../docs/engineering/m3-media-contract.md)。
