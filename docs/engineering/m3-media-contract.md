# M3 媒体处理边界与首批实施说明

更新日期：2026-10-19。状态：M3 进行中。M3.1 已实现独立 Go 图片内核；M3.2 首批实现 Java 上传意图、资源、待处理任务及待投递记录，功能默认关闭。租约、消息发布/消费和结果回传仍为后续契约，尚未接通网站异步处理链路。首批登记说明见[实现记录](m3-media-registration-2026-10-19.md)。

## 1. 当前上传链路与兼容要求

现有 [UploadController](../../backend/src/main/java/com/example/demo/storage/controller/UploadController.java) 的 `uploadImage` 检查登录用户，按用户/IP 限流，调用 [StorageService](../../backend/src/main/java/com/example/demo/storage/service/StorageService.java) 后同步返回 `200 + {url}`。[前端上传调用](../../frontend/src/api/uploads.ts) 发送 multipart 字段 `file`，只读取 `url/path`；发帖、编辑、评论和资料设置均直接保存这个地址。

存储服务按优先级选择 S3 或本地 provider。本地 provider 写随机对象键并返回 `/uploads/...`；S3 默认返回稳定 `/uploads/s3/{token}`，由 [StoredImageController](../../backend/src/main/java/com/example/demo/storage/controller/StoredImageController.java) 重定向至短时签名地址。现有 `/uploads/**` 为公开读取。

进入 M3 前没有持久化媒体资源、归属、任务版本、处理结果或孤立对象清理。新登记链路在显式开启时保存资源、归属、第一版任务及待投递记录；尚未实现派生结果或孤立对象清理。原始 URL 继续公开可读，状态 API 的 owner 检查不改变这一事实。M3 不能把原接口直接改为 `202 + jobId`。

首版继续返回可读原图 URL；启用异步处理后，可增量返回 `assetId`、`jobId`、`processingStatus`。处理未完成或失败时仍可使用原图；不会改写历史 Markdown 或替换旧地址的含义。历史 URL 不自动补认领为某个用户的私有资产。

## 2. 数据所有者和接口边界

| 范围 | Java 核心 | Go worker |
| --- | --- | --- |
| 用户与授权 | 当前登录用户、资源归属、任务访问权、内部服务身份 | 不接收用户令牌，不访问用户/帖子数据库 |
| 持久化状态 | 资源、原始 provider/key/hash、任务版本/尝试/租约、结果与请求 Outbox | 只有执行中的临时状态，不直接写核心表 |
| 图片内容 | 保存原图，签发受控读写能力，接受派生结果并持久化其引用 | 校验输入摘要，按固定规格处理并返回输出摘要/尺寸/格式 |
| 完成判定 | 数据库已接受当前有效任务版本的结果 | Java 确认持久化成功后才 ACK 请求 |

新增 owner Service 放在 `storage` 域；跨域调用只传公开 DTO/Service，不新增 identity/content Entity 或 Repository 依赖。provider 已拆分准备与写入，内部计划包含 provider、原始对象键、URL、字节数与 SHA-256，原上传方法兼容保留。

消息 envelope 只包含稳定 `eventId`、`schemaVersion=1`、`eventType=MEDIA_PROCESSING_REQUESTED`、`jobId`、`jobVersion`、`assetId`、固定处理规格/处理版本、发生时间和可选关联 ID。不放原图字节、用户令牌、S3 凭据、任意外部 URL或本机路径；Go 不能把任意消息字段当作可读取的文件地址。

初版由 Java 按任务和当前租约提供受控原图读取、接收派生图；如后续采用对象存储直传/直读，签名能力必须绑定原任务的精确对象键、操作和期限。两种传输都不能允许客户端指定任意 HTTP 地址，或允许 Go 读写整个上传目录/存储桶。

## 3. 版本、租约和幂等

- `jobVersion` 与执行尝试次数分开。自动重投保留原事件 ID/版本；用户重新处理创建新任务版本与新请求事件。原图对象保持不可变，重新上传建立新资源。
- Java 条件领取任务，持久化 `PROCESSING`、递增尝试次数、新租约标识和期限。旧租约不能续租、完成或覆盖新领取者的状态；lease 标识不能写入公开日志或消息。
- 建议状态为 `PENDING → PROCESSING → SUCCEEDED / FAILED / CANCELLED`。非终态过期领取由可靠恢复机制再次投递；超过上限进入可诊断失败。`BUSY` 不能被 worker 直接当作任务已完成；首版以受控延迟重试处理，避免 ACK 后没有执行者。
- 结果带 `jobId + jobVersion + 当前租约 + 处理版本 + 输入/输出 SHA-256 + 格式/尺寸/字节数`。Java 重新验证输入绑定、输出实际格式和限制，在数据库中按版本/租约条件接受。过期、取消或旧版本结果不能更新当前资源映射。
- 同一已接受版本的相同结果重传返回既有结果；相同版本的冲突结果不覆盖，进入可诊断冲突。Java 返回成功后，Go 才确认 broker 消息；回传超时允许查询/重传确认，不能把网络超时直接当作未提交。
- 派生对象键由 Java 根据资源、任务版本、处理版本和输出摘要生成，不接受调用方路径。输出对象不可覆盖原图；不同尝试产生不同字节时保存在不同摘要键下，只有通过数据库条件更新的结果才成为当前映射。
- 对象写入和 SQL 提交不是分布式原子事务。SQL 失败或租约过期后留下的对象需延迟对账清理；不能在响应不确定时立即删除可能已经被接受的对象，也不能清理仍由有效任务使用的输出。

相同处理版本应固定实现、编码器构建与规格；变更构建时建立新处理版本。内核测试中的确定性指同一构建与输入，不承诺不同 Go/编码器版本逐字节相同。租约、处理预算、存储/回调超时与退出宽限分别配置，依据测量确定，不复制内容事件的 30 秒租约。标准库 CPU 解码的 context 检查是协作取消，不能当作操作系统级硬超时。

## 4. 首批 Go 内核范围

目录为 `services/media-service/internal/processor`，使用 Go 标准库，模块语言基线 1.26。仅处理 JPEG 与静态 PNG：识别真实格式、先检查尺寸/像素预算，再完整解码；按 `thumbnail`（480×480）或 `preview`（1600×1600）盒内等比例缩小，不放大；JPEG 质量 82，PNG 保留透明。应用 EXIF 方向后重新编码，移除原始元数据。

固定输入/输出上限各 10 MiB、像素上限 2400 万、单边上限 20000；GIF、WebP、SVG、APNG 不在本批支持范围，失败保留原图路径。处理结果包含真实格式、尺寸、字节和 SHA-256。图片尺寸预检查依据 [Go image 文档](https://pkg.go.dev/image#DecodeConfig)；输入格式、预算与输出必须同时校验，不能只信文件名或 MIME。

本机已有 Go 1.26.2，仅用于当前原型验证；当前 1.26 补丁版本从 [Go 官方下载索引](https://go.dev/dl/?mode=json) 核对为 1.26.9。后续发布构建固定受支持的补丁版本与构建标识，不改用户的全局 Go 安装。本批没有增加 RabbitMQ 客户端、网络监听、数据库账号或存储凭据。

## 5. 分批计划与验收

| 批次 | 范围 | 通过条件 |
| --- | --- | --- |
| M3.1 当前首批 | 上传调用链调查、本文契约草案、Go 图片内核 | 合成图片验证真实像素与尺寸、方向/透明/限制/取消；gofmt、Go 测试和 vet；race 单列实际结果 |
| M3.2 | Java 资源/任务/Outbox 首批已实现，受控输入/结果接口、Go 消费生命周期待做 | 真实上传→消息→Go→结果落库→API/存储回读；重复、冲突、过期租约、回调响应丢失、broker 重启与孤立对象处理 |
| M3.3 | 手机处理状态反馈、完整回退、通知 SSE | 账号切换不污染状态，原图仍可读；持久化收件箱补拉、断线/令牌生命周期与浏览器回归 |

下一批具体文件计划：在 `storage/dto`、`storage/entity`、`storage/repo`、`storage/service` 内新增 owner 模型，追加兼容迁移；扩展 provider 内部结果 DTO 和受控读写方法；新增独立媒体事件发布/任务接口，继续使用 content 与 notification 各自的队列契约。Go 在内核通过后再增加 `cmd/media-worker`、consumer、coreclient 和 storage 适配。本批不创建空壳目录来代表这些能力已经完成。

M3 完整完成需要真实上传和任务持久化回读、消息恢复及通知 SSE 验收；Go 内核的单元测试不能替代这些门槛。

## 6. 首批本地验证结果

主代理已审查内核及测试，并在最终文件状态执行 gofmt 检查、`go vet ./...` 与 `go test -race -count=1 -json ./...`：12 项主测试、14 个表驱动子场景、1 个包通过，0 fail/skip。覆盖实际解码后的输出尺寸、透明通道、八种方向像素、重编码元数据移除、坏图/格式/输入/像素/输出限制、取消和并发重复结果。结果保存在 `outputs/m3-20261019/final/summary.json` 与 JSONL，实际 Go 版本为本机 1.26.2，GCC 仅在当前进程 PATH 中使用。

新增 `media-kernel` CI job 使用 Go 1.26.9/Linux、race、vet、格式门禁和非空/零跳过执行证据门禁。工作流已通过 YAML 解析，仓库引用/Java 架构检查通过；本批仍为本地未提交改动，新 job 尚未在远程执行。这些结果不代表真实上传、MQ、Java 回传或对象存储已经集成。
