# 本地开发与 Compose 手册

从仓库根目录执行本页命令。PowerShell 开发模式在宿主机运行应用，Compose 模式在容器中运行应用。两种模式共用默认端口，选择其中一种启动。需要临时账号和独立数据卷时，使用 [隔离 Demo](demo-rehearsal.md)。

## PowerShell 本地开发

准备 Windows、PowerShell 7、Git、已启动的 Docker Desktop、JDK 21 或更新版本和 Maven。安装 Node.js 22.13 或更新版本及 pnpm 11.5.0，版本要求以 [frontend/package.json](../../frontend/package.json) 为准。

```powershell
cd frontend
pnpm install --frozen-lockfile
cd ..
./scripts/Start-Local.ps1
```

启动成功后打开 [http://127.0.0.1:5173](http://127.0.0.1:5173)。如果 `JAVA_HOME` 未指向可用的 JDK，可执行 `./scripts/Start-Local.ps1 -JavaHome 'C:\path\to\jdk-21'`，把路径换成本机安装位置。首次启动需要下载依赖、构建 Java 模块并执行测试。已有构建产物时可用 `-NoBuild` 跳过构建，启动仍会执行就绪检查。

| 默认端口 | 用途 |
| --- | --- |
| 5173、9000 | 前端、API 网关 |
| 8080、8081、8092 | Core、通知服务、可选 Go 实时聊天 |
| 13306、16379、15673、25673 | MySQL、Redis、RabbitMQ、RabbitMQ 管理端 |

脚本先等待依赖就绪，再启动通知服务、Core、网关、可选 Go 实时聊天和前端。Java 服务通过 `/actuator/health` 后继续，Vite 前端需能访问 `/`。必需端口冲突会中止启动。Go 构建、端口或健康检查遇到问题时，聊天可回退到 HTTP 轮询。

```powershell
./scripts/Test-Local.ps1
./scripts/Stop-Local.ps1
```

接口冒烟会创建测试账号、帖子和评论。停机脚本核对 PID、启动时间和工作区路径，归属匹配才停止相应进程。停机会保留数据库卷、上传文件和运行凭证。只需要停止应用时，用 `./scripts/Stop-Local.ps1 -KeepDependencies` 保留依赖容器。

运行状态在 `test-artifacts/local-runtime/`。其中 `runtime.env` 与 `test-account.json` 含本机凭证，只保存在本地。首次启动生成随机凭证，已有 `runtime.env` 按原内容读取。旧文件缺失 `RABBITMQ_MANAGEMENT_PORT` 或 `VITE_AUTH_VERIFY_MODE` 时，脚本会在进程内补齐，默认值分别为 `25673` 和 `off`。启动会检查必要配置、标识符、回环地址、端口和凭证格式，结束时恢复调用进程的相关环境变量。

| 启动问题 | 检查入口 |
| --- | --- |
| 找不到 JDK 或 `javac` | 检查 `JAVA_HOME`，或传入 `-JavaHome` |
| 找不到前端依赖 | 在 `frontend/` 执行 `pnpm install --frozen-lockfile` |
| Docker 未就绪或端口冲突 | 检查 Docker Desktop 和上表端口 |
| 应用未通过健康检查 | 查看 `test-artifacts/local-runtime/` 下的服务日志、错误日志和 `build.log` |

## 完整 Compose

先复制 [环境变量模板](../../.env.example)，填入必填账号和随机密钥。首次启动前保持 `APP_ADMIN_EMAIL` 为空、`APP_ADMIN_USER_ID=0`。如需覆盖 MySQL JDBC URL，按 [MySQL 时区说明](mysql-jdbc-timezone.md) 保留 `serverTimezone=UTC&forceConnectionTimeZoneToSession=true`。

```powershell
Copy-Item .env.example .env
# 填好 .env 后继续
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --build
```

访问 [http://127.0.0.1:5173](http://127.0.0.1:5173)。停止时执行 `docker compose --env-file .env down`，保留命名卷中的数据库和上传文件。使用 `down -v` 会删除命名卷，先确认数据已有备份。开发配置仅绑定本机回环地址，使用本地认证和 Mock AI。浏览器旅程、重启和数据恢复见 [运行验收](m0-runtime-verification.md)。

## 管理员初始化

管理员初始化默认关闭。先用可信账号登录，通过当前用户接口读回持久化 ID。在 PowerShell 模式的私有 `test-artifacts/local-runtime/runtime.env`，或 Compose 模式的私有 `.env` 中设置 `APP_ADMIN_USER_ID`，再重启后端。可用 `APP_ADMIN_EMAIL` 核对邮箱一致性。仅配置邮箱时，邮箱必须已经验证，后端才会接受配置。

社区治理首次初始化时授予管理员角色，后续以数据库中的角色为准。初始化后可以清空两个选择器。旧库升级后，既有联系方式的验证标记默认为 false。升级前可以清空历史邮箱选择器，或改用已核对的可信用户 ID。

## 通知库独立账号

新生成的本地 `runtime.env` 包含通知运行账号和 Flyway 迁移账号。现有本地环境先备份，用 `./scripts/Stop-Local.ps1 -KeepDependencies` 停止应用，再在原运行配置中设置以下四项，最后重新启动。

- `NOTIFICATION_DB_USER` 与 `NOTIFICATION_DB_PASSWORD`
- `NOTIFICATION_DB_MIGRATION_USER` 与 `NOTIFICATION_DB_MIGRATION_PASSWORD`

完整 Compose 在私有 `.env` 设置同样四项。运行与迁移账号各自独立，并与核心库及数据库管理员账号区分。生成 24 至 128 位随机密码，字符使用字母、数字或 `_+=@%./:-`。新卷随 MySQL 启动初始化。已有卷先备份，在原 Compose 项目按顺序执行，每步成功后继续。

```powershell
docker compose --env-file .env stop backend notification-service
docker compose --env-file .env up -d --wait mysql
docker compose --env-file .env exec -T mysql sh /docker-entrypoint-initdb.d/01-notification-db.sh
docker compose --env-file .env up -d --wait --build
```

初始化会保留通知表、Flyway 历史和数据卷。脚本可以重复执行，将通知运行账号限制为本库的 `SELECT`、`INSERT`、`UPDATE`，Flyway 通过迁移账号执行，并收回核心账号对通知库的旧权限。

兼容配置中只设置运行账号时，该账号兼做迁移。四项省略时沿用共享授权。需要回切时，停止两个应用并备份，调整配置后再执行初始化。自管部署可向通知服务单独传入 `SPRING_FLYWAY_USER` 和 `SPRING_FLYWAY_PASSWORD`，沿用原通知库连接地址。独立运行验证见 [通知投递与运维](notification-delivery.md#数据迁移与启用顺序)。

## 独立聊天部署

用干净的 Compose 项目演练。按 [环境变量模板](../../.env.example) 设置聊天库的独立运行账号、迁移账号及管理员密码，再分别生成 `CHAT_INTERNAL_SERVICE_TOKEN` 和 `CHAT_GOVERNANCE_INTERNAL_TOKEN`。聊天应用由 [chat-service/Dockerfile](../../chat-service/Dockerfile) 构建，通过私有网络向 Core 获取认证、公开资料和联系判定。网关继续提供原有聊天 API 与 WebSocket 入口。

```powershell
docker compose --env-file .env -p bluealbum-chat-drill -f docker-compose.yml -f docker-compose.chat.yml up -d --build --wait
```

隔离验收入口为 [compose-browser-smoke.sh](../../scripts/ci/compose-browser-smoke.sh)。脚本使用临时数据，验收结束后清理本次项目和卷。

| 模式 | 验收范围 |
| --- | --- |
| `BLUEALBUM_SPLIT_CHAT_CHECK=1` | API、浏览器、数据库归属与权限 |
| `BLUEALBUM_CHAT_REALTIME_CHECK=1` | 双 Go 节点实时信号、节点停机、重连补拉和账号隔离 |
| `BLUEALBUM_CHAT_CUTOVER_CHECK=1` | 历史切换、审核补发、反向对账和回退 |
| `BLUEALBUM_CHAT_BACKUP_CHECK=1` | 核心、通知、聊天恢复到独立项目。需要 PowerShell 7.4 或更新版本 |

已有聊天历史的环境先按 [数据库切换手册](../architecture/03-数据库归属与迁移.md#聊天首次分库演练) 做迁移和对账。Compose 可叠加 `-f docker-compose.observability.yml`，启用私网 Prometheus，采集 Core Outbox 和各 Go 实时节点状态。[服务间通信](../architecture/04-服务间通信规范.md) · [指标与告警](observability.md#微服务联调指标)
