# M5 稳定性收敛

在同一个已推送提交上完成默认与独立聊天部署的运行验收，并用可追溯的报告证明消息可补拉、授权时序符合约定、数据可切换与恢复。此前的验收报告用作回归参考。

## 2026-11-24 基线

- 远端 `main` 为 `a6fa06f46f680284834300271a824bfe13ae431a`。Git 实际统计较 `6b08b93` 变更 304 个文件。搜索、Kafka、PostgreSQL、gRPC 和遥测按已有实现及试点登记。
- GitHub Actions 的 `backend`、Go、`python-tests` 已通过。`repository` 的清理测试失败，`frontend` 的 Nginx DNS 恢复验收因预期三个变量代理而实际有四个失败。依赖这些门禁的运行验收被跳过。当前工作区的 Java 依赖和仓库静态检查均通过。[CI 运行](https://github.com/17356085/MagicAlbum/actions/runs/45317183589) · [仓库结构失败](https://github.com/17356085/MagicAlbum/actions/runs/45317183589/job/138186726798) · [前端失败](https://github.com/17356085/MagicAlbum/actions/runs/45317183589/job/138186726881)
- 已核对本地报告。默认部署的 API、Playwright 和 Selenium 共 16 项通过，分库组合部署 148 项通过，均零跳过。另有双节点实时、切流回退、备份恢复、Kafka 三轮演练与通知库 PostgreSQL 双向迁移 41 项的隔离验收记录。这些记录给出了可复用的用例。下一次验收报告需明确记录受测提交和对应配置。[历史运行报告索引](08-部署与运维设计.md)

## 本轮验收

| 审计清单 | 当前证据 | 完成条件 |
| --- | --- | --- |
| ARCH-01 · 构建与 CI | 两个失败已在远端日志中定位。运行专项被跳过 | 仓库清理用例走到预期的 Compose 清理调用，Nginx 验收按当前路由检查变量代理。修复后在同一 SHA 上运行仓库、Java、前端、Go 与专项验收，记录 job 链接、结论和预期内的跳过项 |
| ARCH-02 · 实时信号丢失 | 前台页面每三秒调度轮询，WebSocket 就绪时每四轮对账一次。已有离线 31 条和双 Go 节点恢复报告。Java 发布队列容量为 256，队列满时会丢弃任务 | 在 WebSocket 保持就绪且未重连时压制 `changed`，接收端在下一次可执行的 REST 对账周期内补齐，应记录耗时。另验证发布队列饱和、断网及重连。按会话序号核对完整、按序且无重复，并能从指标区分队列拒绝与发布异常 |
| ARCH-03 · 联系授权时序 | 分库实现按核心的关系判定时点授权，结果有效期为两秒。已有授权过期时回滚消息和额度的测试 | 用同步点覆盖先拉黑再判定、先取得授权再拉黑、授权在提交前检查时已过期。拉黑后取得的新判定拒绝发送，已取到的在途授权按现有约定处理，过期失败无消息及额度写入。使用相同 `clientMessageId` 重试核对落库和额度次数 |
| ARCH-04 · 切流与恢复 | 已有前向复制、独库写入、回退与备份恢复报告 | 对当前受测版本重跑同库到分库及反向对账。校验消息、序号、已读位置、额度、审核状态及回执。验证分库写入期间核心旧表冻结，恢复后仍可按原消息 ID 去重 |
| ARCH-05 · 数据与凭证边界 | 组合部署记录中已有运行账号 DDL、核心到聊天库、聊天到核心库的拒绝证据 | 在独立部署中，以实际运行和迁移账号核对所属库许可及跨库拒绝。覆盖通知库、聊天专库及核心的边界。内部接口验证正确凭证可用，错误用途凭证及公网转发被拒绝 |

首批先处理两个 CI 门禁。随后并行复核长连接丢信号和权限时序，最后对同一个候选提交执行双拓扑、切流及恢复。故障用例使用隔离 Compose 项目和合成账号，报告记录 SHA、运行配置、用例结果及业务数据对账。

[默认与分库浏览器入口](../../scripts/ci/compose-browser-smoke.sh) · [双节点实时验收](../../scripts/ci/compose-chat-realtime.sh) · [切流回退验收](../../scripts/ci/compose-chat-cutover.sh) · [备份恢复验收](../../scripts/ci/compose-chat-backup.sh) · [现有联系授权语义](05-社交关系设计.md#并发配额和失败)

## 复现

每项运行验收使用独立的 Compose 项目。授权时序测试先锁住合成账号的额度行，用 MySQL 的锁等待记录确认聊天写入已取得核心授权，再执行拉黑或等待授权到期。报告核对序号、消息条数和额度。凭证核验使用 Compose 部署配置中的实际账号，并记录三项内部接口的授权结果。[授权时序验收](../../scripts/ci/chat-auth-timing.py) · [凭证边界验收](../../scripts/ci/compose-chat-permissions.sh)

```bash
bash scripts/ci/compose-browser-smoke.sh
BLUEALBUM_SPLIT_CHAT_CHECK=1 bash scripts/ci/compose-browser-smoke.sh
BLUEALBUM_CHAT_REALTIME_CHECK=1 bash scripts/ci/compose-browser-smoke.sh
BLUEALBUM_CHAT_AUTH_TIMING_CHECK=1 bash scripts/ci/compose-browser-smoke.sh
BLUEALBUM_CHAT_CUTOVER_CHECK=1 bash scripts/ci/compose-browser-smoke.sh
BLUEALBUM_CHAT_BACKUP_CHECK=1 bash scripts/ci/compose-browser-smoke.sh
```

每一轮的 `source-revision.json` 记录 Git 提交、工作区是否干净及实际启用的配置。GitHub Actions 在候选分支 `codex/m5-*` 推送后运行，上传上述元数据及验收报告。[CI 配置](../../.github/workflows/ci.yml)

## 后续里程碑

| 里程碑 | 审计任务 | 启动依据 |
| --- | --- | --- |
| M6 · 领域自治 | ARCH-06、07、09。优先评论用例的身份与缓存依赖，以及关注、好友申请和拉黑语义 | M5 运行基线可复现，现有接口与数据规则有回归证据 |
| M7 · 工程自治 | ARCH-08、12、13、14。评估聊天独立构建，继续检验 RPC、BFF 与端到端追踪边界 | 双拓扑与回退已验收，拆分收益、契约兼容及回滚成本明确 |
| M8 · 数据与事件 | ARCH-10、11、17。收口 Kafka 失败治理，复核通知 PostgreSQL 双向迁移，评估进一步切换 | 现有两套实现的故障、重放、对账和回退达到验收条件 |
| M9 · 内容分发 | ARCH-15、16。验证 Feed 读模型及服务提取的业务和性能收益 | 有查询成本、内容可见性与分页证据 |
| M10 · 云原生扩展 | ARCH-18。按发布规模、资源和运行数据评估新的基础设施 | 有可度量需求与运维成本估算 |
