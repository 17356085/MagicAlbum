# 并发测试方案（本地 perf 配置）

本方案用于在一次具体的隔离压测环境中记录并发阶梯、稳态和读写比例下的
首次配置门槛失效点。它不能单独证明系统的生产容量、稳定承载能力或生产
可用性；结论必须随环境、提交和完整结果一起归档。

## 配置门槛

[`k6/core.js`](k6/core.js) 当前配置的 k6 阈值为：

- P95 ≤ 300 ms；
- P99 ≤ 500 ms；
- `http_req_failed` ≤ 0.1%。

这些是本次脚本的测试门槛，不是已经验证的 SLA。Prometheus 的 API P99、5xx、
JVM 死锁和 Hikari 告警来自另一套采集窗口；出现 k6 通过并不意味着这些
服务端告警一定通过。发回复的 429 还需要区分设计内限流与真实错误，因为
脚本的业务检查和 `http_req_failed` 的统计口径并不相同。

## 前置条件

从仓库根目录启动完整隔离环境：

```bash
pwsh -File perf/New-PerfEnvironment.ps1
docker compose --env-file perf/.env.perf -f perf/docker-compose.perf.yml up -d --build
```

也可以先按 [`perf/README.md`](README.md) 的说明检查 Docker、端口和凭据配置。
入口为：

- API 压测入口：<http://localhost:8088>
- Prometheus：<http://localhost:9099>
- Grafana：<http://localhost:3001>

压测容器网络内的默认目标是 `http://nginx:8080`。数据库、Redis、RabbitMQ
和 Grafana 的凭据从私有 `perf/.env.perf` 注入，仅适用于本机隔离实验。
封装脚本和直接命令使用同一份文件，Compose 将合成账号密码注入 k6，
没有内置默认密码。已有私有文件时跳过生成命令，生成器会拒绝覆盖。
新版项目及旧卷处理方式见 [`perf/README.md`](README.md)。

## 1. 阶梯并发测试

[`run-concurrency-tests.sh`](run-concurrency-tests.sh) 的阶梯档位是 10、25、
50、100、200 VU。每档默认由 2 分钟升压、15 分钟稳态、2 分钟降压组成：

```bash
bash perf/run-concurrency-tests.sh step
```

单独缩短稳态时间（仅用于快速检查排程，不应冒充正式结果）：

```bash
STEADY_DURATION=5m bash perf/run-concurrency-tests.sh step
```

脚本默认把 VU 数乘以 `USERS_MULTIPLIER=2` 作为 `USERS` 注册合成用户，
并使用 `WRITE_RATE=0.2`、`THINK_TIME_S=0.3`。每档先记录 k6 的 P95、P99、
`http_req_failed`，再结合 API 5xx/429、JVM、Hikari 和数据库指标解释。

记录“首次门槛失效点”时，应写明触发的是哪个指标、哪个 VU 档位和完整
运行参数。这个点只适用于本次环境和测试数据，不能外推为生产容量。

## 2. 稳态并发测试

默认 50 VU，稳态 30 分钟，前后各 2 分钟升降压：

```bash
bash perf/run-concurrency-tests.sh soak
```

例如 100 VU、15 分钟稳态：

```bash
SOAK_VUS=100 SOAK_DURATION=15m bash perf/run-concurrency-tests.sh soak
```

除延迟和错误率外，还要记录是否出现持续的连接池 pending、JVM 堆增长、
GC 后不回落、线程增长、慢查询或锁等待。一次稳态运行只说明该次样本的
观察结果；没有完整结果和资源证据时，应标为“未验证”。

## 3. 读写比例并发测试

默认固定 50 VU，依次运行 `WRITE_RATE=0`、`0.1`、`0.2`、`0.5`：

```bash
bash perf/run-concurrency-tests.sh write-matrix
```

调整矩阵并发为 100 VU：

```bash
MATRIX_VUS=100 bash perf/run-concurrency-tests.sh write-matrix
```

写入比例升高时，单独比较 `threads_create`、`posts_create`、429、MySQL
写入 QPS、锁等待、连接池 pending 和 RabbitMQ 队列；不要只看全局
`http_req_failed` 就断言业务失败原因。

## 4. 结果归档

每次 k6 执行会生成：

```text
perf/results/summary-*.json
```

每轮另写一份 Markdown 记录，至少包含：

- 测试日期、Git 分支和提交；
- API 实例数、数据库、Redis、RabbitMQ 和 Compose 配置；
- VU、stage、`WRITE_RATE`、`THINK_TIME_S`、`USERS`；
- P95/P99、`http_req_failed`、吞吐量和检查通过率；
- CPU、内存、连接池、数据库和队列指标；
- 首次配置门槛失效点、429 的解释和未验证项。

`perf/results/` 已有文件是历史记录。除非重新执行并保存上述证据，不要
把历史数据写成当前提交的性能达标或生产容量结论。

## 5. 排程时间

按脚本默认 stage 计算的只是预计排程时间，不是运行速度或性能结果：

- 阶梯：5 档 × 19 分钟，约 95 分钟；
- 稳态：约 34 分钟；
- 读写矩阵：4 档 × 19 分钟，约 76 分钟；
- `all` 合计约 205 分钟，另加镜像构建、服务启动和归档时间。

建议先运行 `step`，再选择首次门槛失效点以下的档位运行 `soak`，最后运行
`write-matrix`。这是测试顺序建议，不是对任何档位的承载保证。
