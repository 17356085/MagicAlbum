# 性能与压力测试（k6 + Prometheus + Grafana）

## 1. 目标与 SLA（默认）

- 关键接口：登录、分区列表、帖子列表/详情、发帖、评论
- SLA（全局阈值，k6 阈值校验 + Prometheus 告警双保险）
  - P99 ≤ 500ms
  - P95 ≤ 300ms
  - 错误率（http_req_failed）≤ 0.1%

## 2. 压测环境（容器化隔离）

目录：[docker-compose.perf.yml](file:///d:/Desktop/BlueAlbum/perf/docker-compose.perf.yml)

- 独立 MySQL：MagicAlbum_perf（端口映射 3307）
- Nginx：对后端多实例做负载均衡（端口映射 8088）
- Prometheus（端口映射 9099）+ Grafana（端口映射 3001）

后端在 perf profile 下额外开启：
- Actuator/Micrometer 指标端口 9090（容器内访问）

## 3. 启动与横向扩展（1× / 2× / 5×）

在仓库根目录执行：

```bash
docker compose -f perf/docker-compose.perf.yml up -d --build
```

横向扩容后端实例数（示例：2×、5×）：

```bash
docker compose -f perf/docker-compose.perf.yml up -d --scale api=2
docker compose -f perf/docker-compose.perf.yml up -d --scale api=5
```

访问入口：
- 压测目标（Nginx）：http://localhost:8088
- Prometheus：http://localhost:9099
- Grafana：http://localhost:3001（admin/admin）

## 4. 运行压测（每级 15min）

默认脚本：[core.js](file:///d:/Desktop/BlueAlbum/perf/k6/core.js)

1× / 2× / 5× 日常流量的推荐做法：
- 固定业务比例（读多写少），逐级上调 VU 目标或 stage target
- 每级持续 15min，记录拐点（首次 SLA 超限时刻、资源饱和度）

示例：15min 稳态（100 VU），前后各 2min 升降：

```bash
docker compose -f perf/docker-compose.perf.yml --profile tools run --rm \
  -e BASE_URL=http://nginx:8080 \
  -e USERS=200 \
  -e WRITE_RATE=0.2 \
  -e STAGES='[{"duration":"2m","target":100},{"duration":"15m","target":100},{"duration":"2m","target":0}]' \
  k6 run /scripts/core.js
```

每次执行会在 `perf/results/` 生成一份 `summary-*.json`。

## 5. 观测指标与瓶颈定位

Grafana 看板：MagicAlbum 性能压测看板（已随容器自动导入）

重点关注：
- 延迟：API P95/P99 曲线（http_server_requests_seconds_bucket）
- 错误率：5xx 比例、超时与重试
- 资源：容器 CPU / 内存 working set（cAdvisor）
- DB：QPS、连接池活跃/最大（MySQL exporter + Hikari）

## 6. 二次验证（优化后回归）

- 保持相同脚本、相同 stage 配置复跑
- 对比 P95/P99 与资源水位（CPU/内存/连接池）
- 验收：SLA 稳定达标且资源利用率处于合理区间（无持续性饱和）
