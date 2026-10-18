# 性能与压力测试（k6 + Prometheus + Grafana）

本文描述仓库内可复现的本地隔离压测流程。配置中的阈值是本次测试的
判断门槛，不是已经测得的成绩、生产 SLO、容量承诺或“生产可用”结论。

## 1. 测试范围与配置门槛

[`k6/core.js`](k6/core.js) 会注册合成用户，并混合执行登录、分区列表、
主题列表/详情、发主题和发回复（posts）等请求。写入比例由
`WRITE_RATE` 控制。

k6 当前配置的阈值为：

- `http_req_duration` P95 ≤ 300 ms；
- `http_req_duration` P99 ≤ 500 ms；
- `http_req_failed` ≤ 0.1%。

[`prometheus/alerts.yml`](prometheus/alerts.yml) 另行配置了 API P99、5xx
比例、JVM 死锁和 Hikari 连接池水位告警。它们与 k6 指标的采集口径不同，
不能互相替代，也不能在没有新一轮实测结果时写成“达标”。特别是，脚本
对发回复的 429 检查允许通过，但 k6 的 `http_req_failed` 仍可能把非成功
HTTP 响应计入失败；报告时要把预期限流和真实错误分开说明。

## 2. 压测环境

环境定义在 [`docker-compose.perf.yml`](docker-compose.perf.yml)，命令从
仓库根目录执行：

首次先生成本机私有随机凭据（PowerShell 7.4+）。已有文件会拒绝覆盖：

```powershell
pwsh -File perf/New-PerfEnvironment.ps1
```

变量清单见 [环境示例](.env.perf.example)。`perf/.env.perf` 被 Git 忽略，
不要提交或分享。随后启动：

```bash
docker compose --env-file perf/.env.perf -f perf/docker-compose.perf.yml up -d --build
```

主要入口和端口如下：

- Nginx 压测入口：<http://localhost:8088>
- Prometheus：<http://localhost:9099>
- Grafana：<http://localhost:3001>
- cAdvisor：<http://localhost:8089>
- MySQL、Redis、RabbitMQ 的宿主机映射分别为 3307、6380、5673；RabbitMQ 管理界面为 15673。

容器网络内 k6 应访问 `http://nginx:8080`。后端管理/Prometheus 端口
`9090` 只在容器网络中暴露给 Prometheus，不是宿主机访问入口。

该 Compose 文件包含仅供本机隔离实验的数据库、消息队列和 Grafana 登录
配置，所有宿主机端口都显式绑定到 `127.0.0.1`。数据库、Redis、RabbitMQ、
Grafana、exporter、JWT 和合成测试账号使用环境注入的独立随机凭据，缺少任何
必填变量时 Compose 解析失败。Redis 开启认证，API 和 exporter 使用同一套
相应服务凭据。Grafana 用户为 `admin`，密码读取私有文件中的 `PERF_GRAFANA_PASSWORD`。

新版默认项目名为 `bluealbum-perf-v2`，使用新卷，不会重用旧 `perf` 项目的
实验数据。旧卷的账号密码不会因修改环境变量而自动轮换；MySQL exporter 的
专用账号初始化脚本也仅在空卷首次启动时执行。需要保留旧数据时，先备份，
再单独规划改密、exporter 授权和手机号唯一约束迁移；不要删除旧卷来绕过问题。
同一份私有文件应贯穿启动、扩容、压测和停止。自定义项目使用 `PERF_PROJECT_NAME`，
所有命令保持一致。

压测 API 使用显式 Mock AI 和内存通知，关闭本地人机校验；只测当前 k6 覆盖的
核心请求，不代表真实 AI、独立通知投递或完整论坛容器验收。`perf` profile 单独
允许 Prometheus 抓取指标，业务接口继续执行鉴权。

执行启动命令只会创建本机隔离压测环境，不表示压测已经运行、结果已经通过，
也不构成生产容量或性能验收证据。

## 2.1 依赖服务轻量验收

在运行 API 压测前，可以用 PowerShell 7.4+ 验证本轮私有凭据、Redis 认证、
MySQL exporter 专用只读账号和两个 exporter 的真实指标：

```powershell
pwsh -File scripts/Test-PerfServices.ps1 -RunId 7f3a91c2d0
```

`-RunId` 必须是十位十六进制标识；`-EnvironmentFile` 默认读取
`perf/.env.perf`，需要其他私有环境文件时显式传入。脚本使用
`bluealbum-perf-check-<RunId>` 项目，只启动 `mysql_perf`、`redis_perf`、
`mysqld_exporter`、`redis_exporter`，不构建源码镜像，也不启动 API、Grafana
或 k6。MySQL 和 Redis 的宿主机端口会被移除，exporter 指标端口使用本机
随机回环端口；凭据文件不会被脚本打印、改写或归档。

Windows 主机在启动前至少需要 1.5 GiB 可用 commit memory；本机完整论坛容器
与浏览器验收要求至少 4 GiB，两者不能混用。结果写入
`outputs/perf-services/<RunId>/checks.json`，脚本结束时会按精确项目名执行
`down --volumes` 并确认项目容器和卷均为零；`Succeeded` 只有所有检查和清理
都成功时才为 `true`。

该入口只验证依赖服务的认证链路和 exporter 指标可用性，不运行 API 请求、
k6 压测或容量测试，因此通过它不能推出 API 性能、吞吐量或生产容量结论。

## 3. 后端实例扩展

Nginx 会把请求转发到 `api` 服务，可以在首次启动后扩展 API 实例：

```bash
docker compose --env-file perf/.env.perf -f perf/docker-compose.perf.yml up -d --scale api=2
docker compose --env-file perf/.env.perf -f perf/docker-compose.perf.yml up -d --scale api=5
```

实例数量、数据库、缓存、消息队列和压测参数必须写入结果归档；不同
环境的结果不可直接横向比较。

## 4. 运行压测

直接运行一次 2 分钟升压、15 分钟稳态、2 分钟降压的示例：

```bash
docker compose --env-file perf/.env.perf -f perf/docker-compose.perf.yml --profile tools run --rm \
  -e BASE_URL=http://nginx:8080 \
  -e USERS=200 \
  -e WRITE_RATE=0.2 \
  -e STAGES='[{"duration":"2m","target":100},{"duration":"15m","target":100},{"duration":"2m","target":0}]' \
  k6 run /scripts/core.js
```

压测用户和写入数据会落在独立的 perf 数据库中；只在该隔离环境运行，
合成账号密码由 Compose 从 `PERF_TEST_PASSWORD` 注入，不提供内置默认值，
不要复用业务凭据，也不要把密码作为命令行参数打印出来。
每次 k6 执行会把 JSON 摘要写入 `perf/results/summary-*.json`。

并发阶梯、稳态和读写矩阵由 [`run-concurrency-tests.sh`](run-concurrency-tests.sh)
封装，完整方案见 [`CONCURRENCY.md`](CONCURRENCY.md)：

```bash
bash perf/run-concurrency-tests.sh step
bash perf/run-concurrency-tests.sh soak
bash perf/run-concurrency-tests.sh write-matrix
# 依次运行全部方案（默认约 205 分钟的排程时间）
bash perf/run-concurrency-tests.sh all
```

该脚本默认使用容器内地址、`USERS_MULTIPLIER=2`、`THINK_TIME_S=0.3`、
阶梯稳态 15 分钟和稳态测试 30 分钟；可通过脚本中声明的环境变量覆盖。
脚本通过 `PERF_ENV_FILE`（默认 `perf/.env.perf`）读取同一份私有配置，合成账号
密码由 Compose 注入。脚本需要 Bash（Linux、WSL 或 Git Bash）以及可用的 Docker Compose。

## 5. 观测与结果解释

Prometheus 配置会抓取 API、cAdvisor、MySQL exporter 和 Redis exporter；
Grafana 看板由 [`grafana/dashboards/magicalbum-perf.json`](grafana/dashboards/magicalbum-perf.json)
自动加载。重点记录：

- k6 的 P95、P99、`http_req_failed`、`http_reqs`、`iterations`、`checks`；
- API 的 5xx、429 和接口级延迟；
- JVM CPU、堆、GC、线程数及 Hikari 连接池；
- MySQL/Redis 指标，以及 RabbitMQ 发布、消费和队列堆积。

`perf/results/` 中已有的 JSON 和 Markdown 是历史归档，不是当前工作树的
新一轮验证。每次正式记录至少写明日期、Git 分支/提交、实例数、数据库、
VU/stage、`WRITE_RATE`、`THINK_TIME_S`、各指标和阈值结果；没有这些证据时，
只能写“未验证”。

## 6. 停止环境

```bash
docker compose --env-file perf/.env.perf -f perf/docker-compose.perf.yml down
```

该命令默认保留命名卷。只有确认不再需要本轮数据时，才使用会删除 perf
卷数据的 `down -v`。

Exporter 凭据接口依据所用版本的 [MySQL exporter 文档](https://github.com/prometheus/mysqld_exporter/blob/v0.15.1/README.md)
和 [Redis exporter 文档](https://github.com/oliver006/redis_exporter/blob/v1.62.0/README.md)。
MySQL 使用专用只读监控账号及密码环境变量，Redis exporter 使用 `REDIS_PASSWORD`。
