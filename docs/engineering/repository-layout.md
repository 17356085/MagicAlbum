# 目录与文件归属

| 位置 | 职责 |
| --- | --- |
| `frontend/` | Vue 应用、前端测试、静态资源及构建配置 |
| `backend/` | 论坛核心 API；按业务域划分 controller、service、dto、entity、repo |
| `gateway/` | 路由与请求关联，避免承载论坛业务 |
| `notification-service/` | 通知鉴权、查询和持久化 |
| `services/media-service/` | 独立 Go 媒体 worker；入口在 `cmd/`，实现和包测试在 `internal/`，Java 持有业务数据 |
| `scripts/` | 本地运维与仓库检查；数据库辅助命令放在 `database/`。`Start-Local.ps1`、`Stop-Local.ps1` 管理本地栈；`Test-NotificationRuntime.ps1` 是独立的通知投递运行时验证入口 |
| `config/` | 多个服务共用的静态检查规则 |
| `tests/` | 跨服务 API 与浏览器测试；模块单元测试仍与模块源码放在一起 |
| `tests/fixtures/` | 测试数据和有明确来源的历史参考快照 |
| `perf/` | 隔离的性能实验、配置与历史结果 |
| `docs/engineering/` | 随仓库交付的工程手册 |
| `docs/` 其他子目录 | 本机历史资料及个人材料，默认不跟踪 |
| `outputs/` | 本机审查报告、截图、归档及迁移清单，默认不跟踪 |
| `test-artifacts/local-runtime/` | 本地运行状态、日志与随机凭证；不要归档或移动正在使用的状态文件 |

根目录保留 README、CONTRIBUTING、PRODUCT、根 POM、Compose 配置及通用工具配置。根 `pytest.ini` 指定唯一 Python 测试树，避免收集运行产物里的历史 conftest。日志、DOCX、崩溃记录和接口响应快照应归到对应位置。

查找入口分别为[工程文档索引](README.md)、[脚本索引](../../scripts/README.md)和[独立服务索引](../../services/README.md)。这些索引按用途组织导航，保留现有源码包和历史文档路径；新文件不要复制一套平行的测试树或模块。

JVM 崩溃记录归档到 `outputs/diagnostics/`，移动时记录原路径、大小和 SHA-256 并校验一致。运行中的 PID、上传目录、数据库卷、私有环境文件、依赖缓存及工具默认的 `target`/`dist` 不参与人工迁移。归档不是删除，`outputs/` 中的诊断文件仍需按可能含有本机信息的资料处理。

本地栈的 Java 应用按 `notification-service` → `backend` → `gateway` 顺序启动，最后启动 `frontend` 的 Vite 开发服务器；Java 服务通过 `/actuator/health`、前端通过 `/` 健康检查后才进入下一步。`runtime.env` 和 `processes.json` 属于 `test-artifacts/local-runtime/`，前者包含本地凭证，后者记录 PID 和启动时间；停机时另行核对进程命令行是否属于本仓库工作区，均不应提交、归档或移动。旧 `runtime.env` 的兼容默认值只在启动进程内补齐，已有密钥不会被脚本重写。

独立通知验证先要求根目录 `mvn -B package`，再由 `scripts/Test-NotificationRuntime.ps1`（PowerShell 7.4+、JDK 21+）在 `outputs/notification-runtime/` 下使用临时容器、端口和凭证运行。验证脚本只清理自身登记的资源；其结果不能作为完整 UI 或生产迁移验收证据。

前端保留当前的 `pages/`、`components/`、`api/`、`stores/`、`composables/`、`types/` 等职责边界。同一个页面的专用组件和状态逻辑放在已有页面子目录中；复用价值明确后再提升为公共组件。没有必要为了目录名称统一而重新命名所有模块或 Java 根包。

Java 主源码位于 `src/main/java`，测试位于 `src/test/java`，包声明必须与目录一致。数据库迁移位于模块 `src/main/resources/db/migration`；`backend/sql/mysql` 中的开发建表脚本与版本迁移用途不同，应保持区分。

顶栏专用逻辑位于 `components/header/`，侧栏的标签、动态和最近浏览位于 `components/sidebar/`；页面和共享外壳只协调这些模块。帖子服务的缓存和读取 DTO 组装分别交给 `ThreadCache`、`ThreadDtoMapper`。审核的领域枚举放在 `moderation/model/`，持久化实体仍放在 `entity/`。可靠通知排队位于核心服务的 `notifications/outbox/`，收件箱持久化与事件去重位于独立通知服务。

新增文件时不要复制整个构建目录、依赖目录或运行数据。`node_modules`、`target`、`dist`、依赖缓存及上传文件留在工具或运行配置约定的位置，不参与人工目录迁移。

当前结构、模块归属与剩余维护风险见[项目结构与代码健康度复审](project-health-2026-10-22.md)，早期边界分析见[全仓代码健康与结构设计审查](project-health-2026-10-18.md)。目录迁移前先确认规则与数据所有权，避免仅移动文件却保留原有依赖问题。
