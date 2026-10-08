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

```bash
docker compose -f perf/docker-compose.perf.yml up -d --build
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
配置。本文不复制其中的默认值；在共享机器、联网环境或任何端口暴露前，
必须先替换这些配置并使用你自己管理的凭据。Grafana 登录信息以 Compose
文件中的 `GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD` 为准，
不要把示例配置当作可复用凭据。

## 3. 后端实例扩展

Nginx 会把请求转发到 `api` 服务，可以在首次启动后扩展 API 实例：

```bash
docker compose -f perf/docker-compose.perf.yml up -d --scale api=2
docker compose -f perf/docker-compose.perf.yml up -d --scale api=5
```

实例数量、数据库、缓存、消息队列和压测参数必须写入结果归档；不同
环境的结果不可直接横向比较。

## 4. 运行压测

直接运行一次 2 分钟升压、15 分钟稳态、2 分钟降压的示例：

```bash
: "${PERF_PASSWORD:?set a disposable PERF_PASSWORD before running}"
docker compose -f perf/docker-compose.perf.yml --profile tools run --rm \
  -e BASE_URL=http://nginx:8080 \
  -e USERS=200 \
  -e PASSWORD="$PERF_PASSWORD" \
  -e WRITE_RATE=0.2 \
  -e STAGES='[{"duration":"2m","target":100},{"duration":"15m","target":100},{"duration":"2m","target":0}]' \
  k6 run /scripts/core.js
```

压测用户和写入数据会落在独立的 perf 数据库中；只在该隔离环境运行，
上例强制通过 `PASSWORD` 提供本轮合成账号密码，不要复用业务凭据。
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
该脚本当前不会把 `PASSWORD` 透传给 k6；需要显式合成账号密码时使用上面的
直接命令。脚本需要 Bash（Linux、WSL 或 Git Bash）以及可用的 Docker Compose。

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
docker compose -f perf/docker-compose.perf.yml down
```

该命令默认保留命名卷。只有确认不再需要本轮数据时，才使用会删除 perf
卷数据的 `down -v`。
