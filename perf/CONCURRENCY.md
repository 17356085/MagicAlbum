# 并发测试方案

## 目标

并发测试用于找到系统的稳定承载区间和首次 SLA 失效点。与单次压力测试不同，本方案按并发阶梯、长时间稳态、读写比例矩阵分别验证。

默认 SLA 与 `perf/k6/core.js` 保持一致：

- P99 <= 500ms
- P95 <= 300ms
- `http_req_failed` <= 0.1%

## 前置条件

先启动完整 perf 环境：

```bash
docker compose -f perf/docker-compose.perf.yml up -d --build
```

确认入口：

- API 压测入口：`http://localhost:8088`
- Prometheus：`http://localhost:9099`
- Grafana：`http://localhost:3001`

在容器网络内，k6 默认压测 `http://nginx:8080`。

## 1. 阶梯并发测试

并发阶梯：

- 10 VU
- 25 VU
- 50 VU
- 100 VU
- 200 VU

默认每个并发级别执行：

- 2 分钟升压
- 15 分钟稳态
- 2 分钟降压

执行命令：

```bash
bash perf/run-concurrency-tests.sh step
```

可调整稳态时间：

```bash
STEADY_DURATION=5m bash perf/run-concurrency-tests.sh step
```

观察重点：

- k6：P95、P99、`http_req_failed`
- API：HTTP 5xx、429、接口级 P95/P99
- JVM：CPU、堆内存、GC、线程数
- Hikari：active / idle / pending / max connections
- MySQL：QPS、慢查询、连接数、锁等待

判定方式：

- 首个出现 P95 > 300ms、P99 > 500ms、错误率 > 0.1% 的 VU 档位，即首次 SLA 失效点。
- 如果只在写入接口出现 429，需要单独区分“预期限流”和“真实错误”。

## 2. 稳态并发测试

默认固定 50 VU，连续 30 分钟：

```bash
bash perf/run-concurrency-tests.sh soak
```

调整为 100 VU、15 分钟：

```bash
SOAK_VUS=100 SOAK_DURATION=15m bash perf/run-concurrency-tests.sh soak
```

观察重点：

- P95/P99 是否随时间持续升高
- `http_req_failed` 是否持续增加
- Hikari active 是否长期贴近 max
- pending connection 是否出现持续堆积
- JVM heap 是否持续上涨且 GC 后无法回落
- 线程数是否持续增长
- MySQL 连接数、慢查询、锁等待是否异常

判定方式：

- 稳态阶段内 SLA 全程达标，资源水位无持续性上升，认为该并发档位可稳定承载。
- 如果前 5 分钟正常、后续逐步变差，优先排查连接泄漏、慢查询、队列堆积、缓存失效和 GC 压力。

## 3. 读写比例并发测试

默认固定 50 VU，分别测试：

- 只读：`WRITE_RATE=0`
- 轻写：`WRITE_RATE=0.1`
- 常规写：`WRITE_RATE=0.2`
- 高写：`WRITE_RATE=0.5`

执行命令：

```bash
bash perf/run-concurrency-tests.sh write-matrix
```

调整矩阵并发为 100 VU：

```bash
MATRIX_VUS=100 bash perf/run-concurrency-tests.sh write-matrix
```

观察重点：

- 写比例升高后 P95/P99 的增长幅度
- `threads_create`、`posts_create` 是否成为长尾延迟来源
- 429 是否随写比例增加
- MySQL 写入 QPS、锁等待、连接池 pending 是否上升
- RabbitMQ 发布/消费是否堆积

判定方式：

- 如果只读稳定、写入比例升高后失败率或长尾延迟明显增加，瓶颈优先定位写链路。
- 如果 429 是设计内限流，应在 k6 脚本中单独统计 429，并明确它是否计入 SLA 错误率。

## 结果归档

每次 k6 执行都会生成：

```text
perf/results/summary-*.json
```

建议每轮测试后新增一份 Markdown 归档，记录：

- 测试日期
- Git commit / 分支
- 环境规模：API 实例数、DB、Redis、RabbitMQ
- k6 参数：VU、stage、`WRITE_RATE`、`THINK_TIME_S`
- P95/P99、错误率、吞吐量
- CPU、内存、连接池、DB 指标截图或摘要
- 首次 SLA 失效点
- 结论与后续优化项

## 预计耗时

默认完整执行 `all`：

- 阶梯并发：5 档，每档 19 分钟，约 95 分钟
- 稳态并发：34 分钟
- 读写比例矩阵：4 档，每档 19 分钟，约 76 分钟

总耗时约 205 分钟，不含镜像构建、服务启动和结果整理时间。

正式执行建议分批运行：

1. 先跑阶梯并发，找到失效点。
2. 再选择失效点以下一档做稳态测试。
3. 最后在稳定档位上跑读写比例矩阵。
