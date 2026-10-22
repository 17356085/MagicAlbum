# 同频 · BlueAlbum

BlueAlbum 是一个 Vue 3 + Spring Boot 的论坛项目，可在本机体验注册登录、分区浏览、发帖与评论、关注动态、收藏、站内通知，以及基础的举报审核。当前交付目标是**本地开发与试运行**；仓库中的本地认证、Mock AI 和端口配置不能直接作为公网部署方案。

> 本文面向刚克隆仓库的使用者。先按下面的 Windows 本地流程启动；设计讨论和历史记录不应替代本页的运行步骤。

## 从 `git clone` 到打开论坛

### 准备环境

- Windows、PowerShell 7、Git、运行中的 Docker Desktop。
- JDK 21 或更新版本（需要 `javac`）、Maven、Node.js 22.13 或更新版本（建议 24）、pnpm 11.5.0。版本要求见 [`frontend/package.json`](./frontend/package.json)；运行前可用 `java -version`、`mvn -version`、`node --version`、`pnpm --version` 检查。
- 空闲的本机端口：`5173`（前端）、`9000`（网关）、`8080`（后端）、`8081`（通知服务）、`13306`（MySQL）、`16379`（Redis）、`15673`/`25673`（RabbitMQ）。启动脚本遇到应用端口冲突会停止，不会终止别人的进程。

在 PowerShell 中运行：

```powershell
git clone https://github.com/17356085/MagicAlbum.git
cd MagicAlbum
cd frontend
pnpm install --frozen-lockfile
cd ..
./scripts/Start-Local.ps1
./scripts/Test-Local.ps1
```

如果 `JAVA_HOME` 未指向可用的 JDK，可在启动时传入你自己的路径，例如 `./scripts/Start-Local.ps1 -JavaHome 'C:\path\to\jdk-21'`。不要照搬其他电脑的 JDK 绝对路径。首次启动会构建 Java 模块并运行测试，时间取决于依赖下载和机器性能。

启动成功后打开 <http://127.0.0.1:5173>。`Test-Local.ps1` 会执行本地接口冒烟检查，**会创建测试账号、帖子和评论**；它不是只读检查。前端通过网关 `http://127.0.0.1:9000` 访问 API，数据库、Redis、RabbitMQ 由独立的本地 Compose 配置提供。

`Start-Local.ps1` 会先等待 MySQL、Redis、RabbitMQ 等本地依赖健康，再在默认构建完成后按以下顺序启动应用：`notification-service`（8081）→ `backend`（8080）→ `gateway`（9000）→ Vite 前端（5173）。三个 Java 服务要先通过各自的 `/actuator/health`，前端要先能访问 `/`，脚本才会继续；这样核心后端启动时通知服务的 V3 幂等迁移和 HTTP 端点已经就绪。`-NoBuild` 只跳过 Maven 构建，仍会执行这套启动顺序和健康检查。

运行目录中的 `runtime.env` 首次不存在时由脚本生成随机凭证；已有文件只读取，不会重写其中的密钥。为兼容旧文件，脚本只在进程内补齐缺失的 `RABBITMQ_MANAGEMENT_PORT=25673` 和 `VITE_AUTH_VERIFY_MODE=off`，不会把默认值写回文件。启动前会检查必需键、数据库／用户标识符格式、回环地址、通知服务 URL、端口范围与重复端口，以及凭证的基本格式；失败时不会停止已有进程。脚本会在结束或失败清理时恢复调用进程原有的相关环境变量。

应用进程的 PID 和启动时间会写入 `test-artifacts/local-runtime/processes.json`；停机时还会核对进程命令行是否包含本仓库工作区路径。`Stop-Local.ps1` 只有在这三项归属检查都匹配时才会停止进程；快照无效、PID 已被其他进程复用或命令行不属于本仓库时，会保留该进程并报告错误。停机仍保留数据库卷、本地上传文件和 `runtime.env`，不要手动删除正在使用的运行状态。

```powershell
./scripts/Stop-Local.ps1
# 代码未变且已构建过时，可在下次启动时跳过 Java 构建
./scripts/Start-Local.ps1 -NoBuild
```

停止脚本保留本地数据库卷、上传文件和随机生成的凭证。`./scripts/Stop-Local.ps1 -KeepDependencies` 仅停止应用，保留依赖容器。运行时文件位于被 Git 忽略的 `test-artifacts/local-runtime/`；其中 `runtime.env` 和 `test-account.json` 含敏感信息，不要提交或分享。

### 常见启动问题

| 现象 | 检查方式 |
| --- | --- |
| 找不到 JDK / `javac` | 配置 `JAVA_HOME`，或使用 `-JavaHome` 指向实际 JDK 目录。 |
| 找不到前端依赖 | 在 `frontend/` 执行 `pnpm install --frozen-lockfile`，不要混用 npm 锁文件。 |
| Docker 未运行或端口被占用 | 启动 Docker Desktop；检查上表端口，保留其他应用并自行解决冲突。 |
| 服务健康检查失败 | 查看 `test-artifacts/local-runtime/` 中对应服务的 `.log` 和 `.err.log`，以及 `build.log`。 |

如果启动失败，先查看 `test-artifacts/local-runtime/` 中相应服务日志，再核对端口、Docker Desktop 和私有环境变量。此目录只留在本机，不应提交。

### 独立通知投递验证

通知可靠投递有独立的运行时验证脚本，不依赖正在运行的本地栈。先使用 PowerShell 7.4 或更高版本、JDK 21 或更高版本和 Docker，并在仓库根目录完成一次 Maven 构建：

```powershell
mvn -B package
pwsh -File ./scripts/Test-NotificationRuntime.ps1 -JavaHome 'C:\path\to\jdk-21'
```

脚本默认使用临时 MySQL／Redis 容器、临时宿主机端口（核心 19080、通知 19081、网关 19090），并在 `outputs/notification-runtime/` 留下检查结果和日志。它只记录并清理自己创建的容器、Java 进程和临时凭证文件；不会使用或清理 `test-artifacts/local-runtime/`。该验证覆盖通知服务 V3、核心 Outbox 迁移、接收端停机后的重试、幂等重放和收件人隔离，但不等同于完整前端 UI 验收或生产迁移验收。

## 异步媒体与实时通知

异步图片预览按 [M3 运行手册](docs/engineering/m3-operations.md) 通过可选 Compose 扩展启用，Java 保留资源权限和任务状态，原图上传保持兼容。通知长连接支持按账号断线补拉，仍以普通列表及持久化记录为准。完成情况和运行证据见[阶段进度](docs/engineering/stage-progress.md)。

## 管理员与审核

管理员初始化默认关闭。先登录可信账号，通过当前用户接口读回其持久化 ID，再在 `test-artifacts/local-runtime/runtime.env`（完整 Compose 使用私有 `.env`）设置 `APP_ADMIN_USER_ID=该用户ID`，重新启动后端。可同时配置 `APP_ADMIN_EMAIL` 核对邮箱一致性；只有邮箱而没有 ID 时，账号邮箱必须已验证，否则拒绝启动。不要仅凭未经核对的注册邮箱选择管理员。

初始化只在社区治理尚未初始化时授予首个管理员；后续角色以数据库为准，重启不会恢复已撤销角色。后台支持成员角色、版主分区、发帖限制、分区管理、举报审核恢复与审计，实际完成情况见[阶段进度](docs/engineering/stage-progress.md)。

初始化后可清空两个选择器，已有角色继续保留。升级旧库时联系方式验证标记默认为 false；如果仍使用旧的邮箱初始化配置，应先清空选择器或改为经过可信登录核对的用户 ID，再启动新版本。

## 另一种方式：完整 Compose

根目录的 [`docker-compose.yml`](./docker-compose.yml) 会构建完整应用镜像，与上面的 PowerShell 原生应用模式**二选一**，不要同时占用相同端口。先复制 [`.env.example`](./.env.example) 为 `.env`，填写所有必填账号和高强度随机密钥，首次启动时保持 `APP_ADMIN_EMAIL` 为空、`APP_ADMIN_USER_ID=0`：

```powershell
Copy-Item .env.example .env
# 用编辑器填写 .env 中的空值后再继续
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --build
```

访问 <http://127.0.0.1:5173>。停止使用 `docker compose --env-file .env down`；不要加 `-v`，否则会删除命名卷中的数据。上传文件有独立命名卷，但仍应自行备份。完整镜像、浏览器旅程和独立数据库／上传恢复已有隔离 CI 运行记录，详见[阶段进度](docs/engineering/stage-progress.md)和[恢复验收](docs/engineering/m0-runtime-verification.md)。这些记录不等于公网生产验收。Compose 仅绑定本机回环地址，并启用本地认证与 Mock AI，不可直接暴露公网。

## 已有能力与边界

| 能力 | 当前本地状态 |
| --- | --- |
| 账号、资料、发帖、评论、分区与搜索 | 草稿与最近浏览按账号保存在当前浏览器；可选 Elasticsearch 提供全文索引、重建和故障降级。 |
| 关注动态、点赞、收藏、站内通知 | 收藏与通知持久化；通知支持 Outbox 可靠投递、SSE 和断线补拉。 |
| 图片上传与预览 | 支持本地与 S3 存储适配；可选 Go worker 异步生成预览，由 Java 管理任务与结果。外部对象存储备份和云 ACL 另验。 |
| 社区管理与举报 | 持久化管理员／版主范围、发帖限制、举报隐藏与恢复、审计；可信用户 ID 初始化规则见上文。 |
| AI 摘要 | 作者或有权限的管理者主动生成，任务版本、取消和预算账本持久化；默认 Mock，受控协议测试通过不能代替真实外部模型验收。 |
| 第三方账号与外部发送通道 | 有适配代码和局部测试；外部账号、凭据及真实投递需单独验收。 |
| 多租户与完整运营后台 | 不在当前可用范围。 |

## 项目结构与验证

```text
backend/               论坛核心 API（8080）
gateway/               前端 API 网关（9000）
notification-service/  站内通知服务（8081）
frontend/              Vue 前端（5173）
services/media-service/ Go 异步媒体 worker
config/                Java 静态检查与共享工程规则
scripts/               本地运行、数据库辅助命令与仓库检查
scripts/ci/            隔离运行验收与证据门禁
scripts/__tests__/     工程脚本回归
tests/                 Python API 与浏览器测试
perf/                  压测实验材料；不代表已达到性能目标
docs/engineering/      目录及编码规范（随仓库交付）
docs/ 其他目录          本机文档与历史资料（默认不跟踪）
outputs/               本机审查结果、截图及文件归档（默认不跟踪）
```

修改代码后可按需运行：

```powershell
mvn -B package
cd frontend
pnpm lint
pnpm type-check
pnpm test
pnpm build
```

Go worker 的检查入口见[服务说明](services/README.md)，工程脚本见[脚本索引](scripts/README.md)。Python 接口和浏览器测试需要隔离环境及专用账号，见 [`tests/README.md`](./tests/README.md)。单元测试、完整 Python 回归和各专项运行门禁分别记录结果；已有 CI 证据以对应提交及实际报告为准。

## 开发资料

- [工程文档索引](docs/engineering/README.md)：按架构、运维、阶段实施和历史评审查找资料。
- [前端说明](./frontend/README.md)：依赖、代理与前端检查。
- [Python 测试](./tests/README.md)：接口及浏览器测试环境。
- [性能实验](./perf/README.md)：隔离压测配置和结果解释；历史数字不是当前性能承诺。
- [通知投递与运维](./docs/engineering/notification-delivery.md)：Outbox、重试、租约、凭证与迁移边界。
- [目录与文件归属](./docs/engineering/repository-layout.md)：共享工程目录及运行产物边界。
- [手机号唯一性与迁移验证](./docs/engineering/phone-uniqueness.md)：MySQL 唯一约束、只读重复检查、冲突响应和隔离验收；已有库部署前先核对重复数据。
- [产品开发与技术演进报告](./docs/engineering/development-roadmap.md)：后续里程碑、微服务边界、Go 媒体处理、搜索、观测及验收计划；属于建议方案。
- [分阶段推进记录](./docs/engineering/stage-progress.md)：M0–M4 实施状态和下一阶段的验收要求。
- [M0 完整容器验收](./docs/engineering/m0-runtime-verification.md)：隔离浏览器旅程、重启持久化、数据库与图片恢复，以及 CI 入口。
- 论坛核心 API 在 `backend/`，入口统一为 `/api/v1`；浏览器经 `gateway/` 转发。具体请求与权限以 Controller、Security 配置和测试为准。

开发和目录调整遵循 [CONTRIBUTING](./CONTRIBUTING.md)。运行 `node scripts/Check-Repository.mjs` 可检查目录、包声明、本地导入和工程文档链接。

`docs/engineering/` 为共享工程手册；`docs/` 中的其他设计、历史记录及个人材料仅保留在本机。克隆用户运行本项目以本文、各模块配置和上述开发资料为准。

当前仓库没有可核实的独立 `LICENSE` 文件，因此本页不宣称开源许可证；再分发或商用前请先确认授权。
