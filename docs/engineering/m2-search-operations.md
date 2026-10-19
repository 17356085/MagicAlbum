# M2 搜索运维说明

本文描述 BlueAlbum M2 搜索的本机 Compose opt-in 方式、维护窗口和故障回退边界。当前搜索仍是单体应用内的模块，Go 拆分属于 M3；本说明不代表 M2 已完成真实环境验收或已经上线。

## 启用方式

默认的 `docker-compose.yml` 保持原样。需要启用 M2 时，把基础文件和搜索 override 一起传给 Compose：

```powershell
docker compose -f docker-compose.yml -f docker-compose.search.yml up -d --build
```

查看合并后的配置时也必须使用同一组 `-f` 参数：

```powershell
docker compose -f docker-compose.yml -f docker-compose.search.yml config --quiet
```

基础 Compose 仍负责 MySQL、Redis、RabbitMQ、通知服务及其健康依赖；override 只为 backend 合并搜索环境变量和 Elasticsearch 健康依赖。Compose 的映射合并会保留 backend 在基础文件中的既有 `depends_on` 条目，并增加 `elasticsearch: service_healthy`。

启动前需要使用该项目已有的 `.env` 和随机凭据。可以参考 `.env.example`，为 `DB_USER`、`DB_PASSWORD`、`DB_ROOT_PASSWORD`、`REDIS_PASSWORD`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`、`JWT_SECRET`、`INTERNAL_SERVICE_TOKEN` 等必需项填入本机随机值；`.env` 只保留在本地，不要把真实值写入 override、文档、命令历史或提交。

override 的搜索设置如下：

- Elasticsearch 使用 `docker.elastic.co/elasticsearch/elasticsearch:8.17.10`，单节点、`ES_JAVA_OPTS=-Xms512m -Xmx512m`，容器和 swap 上限均为 `1g`。
- 数据写入命名卷 `elasticsearchdata`，以便重启后保留索引。
- Elasticsearch 没有 published port，只能通过 Compose 内部网络以 `http://elasticsearch:9200` 访问。
- `xpack.security.enabled=false` 仅用于这个没有外露端口的受信开发网络；它不是生产安全配置，不能把该设置复制到对外暴露的 Elasticsearch。
- `ELASTICSEARCH_REPLICAS=0` 适合本机单节点演示；生产副本数、认证和网络隔离必须按部署环境另行配置。
- Elasticsearch 健康检查等待 `/_cluster/health?wait_for_status=yellow`，backend 只有在该服务 `healthy` 后才启动。
- override 不把异步 RabbitMQ 纳入核心 HTTP 健康判定，避免 broker 停机时撤下仍可读写 MySQL 的服务；RabbitMQ 自身健康检查与内容 Outbox 积压诊断继续保留。
- 私有 Elasticsearch 可通过 `ELASTICSEARCH_API_KEY` 配置 API key；启用 TLS 时使用 HTTPS 地址，凭据不放在 URL 中。

## 搜索读取和最终一致性

索引是最终一致的投影，不是帖子权威数据源。搜索命中只携带候选帖子的 ID 和内容版本；backend 会在共享数据库中锁定并重新读取当前 owner 行，核对仍为 `NORMAL` 且版本一致后，再用当前查询视图组装响应。任一候选失效、索引不可用或尚未就绪时，整个请求回退数据库；新增帖子尚未进入索引时仍可能暂缺结果，这一延迟不等于正文丢失。

首次启动或新索引尚未就绪时，搜索会回退数据库。故障回退使用 owner 的直接查询投影，绕过 Redis/MyBatis 搜索缓存；数据库查询继续使用现有 `q`、`tag`、`sectionId`、`page`、`size` 参数契约。搜索路径会把页码规范到至少 1，把大小限制在 1–10，并对空白的查询和标签按空值处理；分区过滤和标签过滤的语义保持不变。

两条路径的匹配能力不同：

- 数据库回退使用现有 SQL `LIKE`，适合简单的标题/正文子串匹配，不提供 CJK 分词、相关性排序或 Elasticsearch 的索引分析能力。
- Elasticsearch 的标题和正文使用 CJK analyzer；标签和分区是精确过滤。列表继续按创建时间、ID 倒序，保持稳定分页，不提供新的相关性排序模式。全文匹配仍受索引最终一致性约束。

无论命中 Elasticsearch 还是发生回退，返回的帖子内容都来自 owner 当前行的重新读取。索引中的墓碑和旧版本用于投影校验，不直接作为对外正文。

## 管理维护窗口

搜索管理端点只有真实管理员 JWT 才能调用。下面的示例只从当前进程环境读取令牌，不在命令内写入真实 token；请在本机通过已有密钥管理方式提供 `BLUEALBUM_ADMIN_JWT`，不要把它提交到仓库。

```powershell
$adminHeaders = @{ Authorization = "Bearer $env:BLUEALBUM_ADMIN_JWT" }
Invoke-RestMethod -Method Get -Uri 'http://localhost:8080/api/v1/admin/search/status' -Headers $adminHeaders
```

可用的管理 API：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/admin/search/status` | 查看当前 alias、ready 和暂停状态 |
| POST | `/api/v1/admin/search/rebuild` | 创建新物理索引、扫描、校验并切换 alias |
| POST | `/api/v1/admin/search/failed/replay?limit=10` | 重放失败内容事件，`limit` 由服务端限制 |
| GET | `/api/v1/admin/content-events/diagnostics` | 查看内容事件 outbox 诊断 |
| POST | `/api/v1/admin/content-events/{id}/retry` | 手动重试指定内容事件 |

`POST /api/v1/admin/search/rebuild` 会先取得数据库中的单例 content-search control 行并暂停内容写入。暂停通过持久化行锁跨实例生效，会等待已经开始的共享 writer 事务提交；不能只依靠某一个 backend 进程的内存标记。重建期间会持续维护租约，按 owner 投影扫描全部帖子（包括隐藏和删除墓碑），刷新并逐项核对新索引。全部扫描和校验成功后，才在同一独占 gate 事务中执行 alias 切换；释放 gate 前不会允许新的内容写入。旧物理索引保留，不会由重建流程自动删除。

重建失败、租约过期或 alias 结果需要人工核对时，不要把“请求返回”当作切换已经完成的证据。先读取 status，并结合 Elasticsearch 实际 alias 和数据库 owner 结果判断。当前没有承诺一个管理员直接 alias 回退 API。

alias 切换请求或数据库提交响应丢失时，重建会在原维护租约内读取实际 alias，核对它是否已经指向逐项验证过的新索引，再补齐数据库状态；不会重复发出切换请求。如果结果仍无法确认，就把当前维护任务的搜索状态设为未就绪，保留旧物理索引并回退数据库。后续读取若发现数据库指针与实际 alias 不一致，也会按原指针条件使状态失效，避免覆盖另一轮已经完成的重建。依赖恢复后，管理员应检查 status 并重新执行完整 rebuild；这里不承诺跨数据库与 Elasticsearch 的分布式原子提交。

## 失败队列与隔离

主队列 `content.search.v1` 与失败队列 `content.search.failed.v1` 使用持久化 quorum queue。主队列配置 `reject-publish` 和 `at-least-once` dead lettering，避免普通死信转发在目标不可用时直接丢弃消息；这些设置遵循 [RabbitMQ quorum queue 说明](https://www.rabbitmq.com/docs/3.13/quorum-queues)。消费在索引写入成功后才确认；可恢复的依赖失败经过有限重试后进入失败队列。

重放时，合法事件经 mandatory publish、路由确认和 publisher confirm 成功后，才确认原失败消息。格式错误、未知 schema 等无法解码的消息会转入 `content.search.quarantine.v1`，确认隔离记录持久化后再确认原消息，并继续处理后面的合法消息。隔离记录仅包含 SHA-256、隔离标识、固定原因和可验证的数值/事件标识，不复制原始正文或凭据；隔离不可用时原消息保留，返回 `QUARANTINE_UNAVAILABLE`。重放响应分别报告 `published`、`quarantined`、`remaining` 与 `failureCode`。隔离队列需要管理员核对，不能当作可直接回放的业务事件。

## 回退和恢复

搜索故障时首选关闭 backend 的 `APP_SEARCH_ENABLED` 并重启 backend，让请求回到数据库路径。由于 tracked override 默认把它设为 `true`，回退时使用额外的本地 override 或部署环境变量覆盖它，不修改项目 `.env` 中的用户秘密，也不要编辑基础 `docker-compose.yml`。例如在本机创建未跟踪的 `docker-compose.search.off.local.yml`：

```yaml
services:
  backend:
    environment:
      APP_SEARCH_ENABLED: "false"
```

然后用三份文件重启 backend：

```powershell
docker compose -f docker-compose.yml -f docker-compose.search.yml -f docker-compose.search.off.local.yml up -d backend
```

恢复搜索时移除这个本地覆盖文件，再使用基础文件加 `docker-compose.search.yml` 重启 backend。关闭搜索开关只改变读取路径；内容事件和数据库 owner 数据仍按各自配置运行。若 Elasticsearch 仍然保留旧索引，重新启用后先确认 status 与 alias，再决定是否安排新的完整 rebuild。

## 验收边界

本文件和 override 只完成静态的 opt-in 入口与运行约定。M2 的真实 MySQL、RabbitMQ、Elasticsearch、跨实例 gate、重建、乱序/重复事件、故障回退及 alias 行为仍需 CI 或目标环境的完整验收；当前不能据此声称已经上线。静态检查应至少覆盖：合并配置可解析、backend 保留基础依赖并等待 Elasticsearch healthy、ES 没有 published port、命名卷存在、健康检查等待 yellow、以及文档中的管理员路径与 Java 控制器一致。
