# M0 Compose 运行时验收维护说明

本文面向维护 BlueAlbum M0 运行时验收脚本的人员，说明本地完整验收和 CI 浏览器冒烟的入口、边界及产物。文档描述的是脚本当前实现，不代表某一次运行已经通过；当前结果必须以该次运行生成的 JUnit、`checks.json` 和备份校验摘要为准。

## 1. 验收入口与覆盖范围

本地完整入口是 [`scripts/Test-ComposeRuntime.ps1`](../../scripts/Test-ComposeRuntime.ps1)。它按顺序完成以下工作：

1. 检查独立 Compose 项目、固定端口和 Python 测试解释器，生成随机凭证和 Compose override。
2. 校验 Compose 配置；默认构建带本次 `RunId` 标签的后端、通知服务、网关和前端镜像，然后等待完整源栈健康。
3. 运行 [`tests/e2e/playwright/test_m0_journey.py`](../../tests/e2e/playwright/test_m0_journey.py)。该浏览器旅程用两个新账号覆盖注册、发帖、评论、点赞、收藏/取消收藏、通知已读与跳转、举报和反向归属权限，并通过 API 重新读回持久化结果。
4. 采集一次 `docker stats` 和五次顺序的本地线程列表请求，产出诊断样本。这里的延迟样本明确标记为“不是容量基准”，不能据此宣称吞吐或性能达标。
5. 创建带图片的持久化数据，重启应用服务并读回；停止源栈的应用服务后调用 [`scripts/Test-ComposeBackup.ps1`](../../scripts/Test-ComposeBackup.ps1) 在独立恢复项目中导入数据库和上传文件。
6. 对恢复前后的数据库表行数与 SHA-256、业务表、上传文件路径/字节数/哈希，以及线程、账号、收藏和图片字节做校验，最后清理本次登记的 Compose 资源。

CI 或手动 Bash 入口是 [`scripts/ci/compose-browser-smoke.sh`](../../scripts/ci/compose-browser-smoke.sh)。它只创建隔离 Compose 项目、构建并启动完整栈、运行同一个浏览器旅程和 JUnit 门禁，然后保存日志并清理项目资源。它不执行数据库转储导入、应用重启后的恢复读回、上传文件字节校验或容量验证；CI 浏览器通过不等于本地完整恢复验收通过。

## 2. 前置条件

### Windows 本地完整验收

- 在 Windows PowerShell 7.4 或更高版本中运行。脚本首行 `#Requires -Version 7.4` 会拒绝较旧的 PowerShell。
- 启动前至少保留 4 GiB 可用系统提交内存。脚本记录 `host-memory-before.json`，不足时在创建容器前退出；这是避免本机内存耗尽的保护门槛，不是生产容量规格。
- Docker CLI、Docker Compose 插件和正在运行的 Docker Engine 必须可用。先在仓库根目录确认：

  ```powershell
  docker version
  docker compose version
  docker info
  ```

  脚本会运行 `docker compose config/build/up/restart/stop/down`、容器复制和容器内 MySQL 命令，因此只有 HTTP 端口可访问而 Docker 不可用并不能满足前置条件。
- Python 3.10 或更高版本的测试虚拟环境必须存在于默认位置 `tests/.venv/Scripts/python.exe`，并安装 [`tests/requirements.txt`](../../tests/requirements.txt) 中的依赖。可以从仓库根目录创建：

  ```powershell
  python -m venv tests/.venv
  .\tests\.venv\Scripts\python.exe -m pip install -r tests\requirements.txt
  ```

- `-BrowserChannel` 默认值为 `chrome`，因此默认需要本机可被 Playwright 识别的已安装 Chrome。若不使用已安装 Chrome，可把参数设为空字符串，让 Playwright 使用 bundled Chromium，并先安装它：

  ```powershell
  .\tests\.venv\Scripts\python.exe -m playwright install chromium
  .\scripts\Test-ComposeRuntime.ps1 -BrowserChannel ''
  ```

  脚本将 `TEST_PLAYWRIGHT_CHANNEL` 传给测试；测试把空值转换为 Playwright 的默认 Chromium。注册流程若遇到外部人工验证会跳过测试，而完整入口会把任何 skipped 视为失败。
- 本地完整入口不读取共享 `tests/.env` 账号。它把 `TEST_EMAIL`、`TEST_PHONE` 和 `TEST_PASSWORD` 设为空，并由测试生成隔离账号；不要把真实账号写进脚本产物或环境文件。
- 源栈和恢复栈共使用 16 个固定宿主机端口，运行前必须全部空闲。脚本没有本地端口参数；端口映射见第 4 节。若需要自定义端口，可使用 CI Bash 入口的 `COMPOSE_*_PORT` 变量，但那条入口仍然只覆盖浏览器旅程。

### CI 或手动 Bash 浏览器冒烟

在 Linux、WSL 或提供 Bash、Python、Docker、`openssl` 的环境中运行。该脚本使用 `/dev/tcp` 检查端口，并用 `openssl rand -hex 32` 创建临时凭证；Windows PowerShell 不能直接替代 Bash 语法。

CI 会安装测试依赖和 bundled Chromium 后调用脚本。手动运行时，除非本机已准备好同样的环境，否则先执行：

```bash
python -m pip install -r tests/requirements.txt
python -m playwright install chromium
```

## 3. 从仓库根目录运行

### 3.1 本地完整 M0 Compose 验收

从仓库根目录执行默认入口：

```powershell
pwsh -NoProfile -File .\scripts\Test-ComposeRuntime.ps1
```

如果当前已经在 PowerShell 7.4 中，也可以执行：

```powershell
.\scripts\Test-ComposeRuntime.ps1
```

入口参数与脚本一致：

| 参数 | 默认值 | 约束与用途 |
| --- | --- | --- |
| `-RunId` | 随机生成的 10 位小写十六进制字符串 | 必须匹配 `^[a-f0-9]{10}$`。它组成源项目 `bluealbum-m0-<RunId>` 和恢复项目 `bluealbum-m0-<RunId>-restore`，并参与镜像标签。每次运行优先使用新的值。 |
| `-ArtifactDirectory` | `outputs/m0-compose` | 会转为绝对路径，且必须位于仓库 `outputs` 目录下；用于日志、报告、测试产物、转储和上传文件副本。 |
| `-PythonExecutable` | `tests/.venv/Scripts/python.exe` | 必须是存在的 Python 文件，并已安装测试依赖。可传相对仓库根目录的路径或绝对路径。 |
| `-BrowserChannel` | `chrome` | 传给 `TEST_PLAYWRIGHT_CHANNEL`。`chrome` 使用已安装 Chrome；传空字符串使用 Playwright bundled Chromium。 |
| `-NoBuild` | 未启用 | 跳过 `docker compose build`。只有当以下镜像已按本次 `RunId` 准备好时才可使用：`bluealbum-m0-backend:<RunId>`、`bluealbum-m0-notification:<RunId>`、`bluealbum-m0-gateway:<RunId>`、`bluealbum-m0-frontend:<RunId>`。 |

显式指定一次运行 ID 和产物目录的例子：

```powershell
.\scripts\Test-ComposeRuntime.ps1 `
  -RunId a1b2c3d4e5 `
  -ArtifactDirectory .\outputs\m0-compose-a1b2c3d4e5
```

重新使用已有镜像标签时才使用 `-NoBuild`：

```powershell
.\scripts\Test-ComposeRuntime.ps1 -RunId a1b2c3d4e5 -NoBuild
```

运行前脚本会拒绝同名 Compose 项目的已有容器或卷，也会拒绝被占用的固定端口。不要在共享或生产项目上修改 `RunId` 以外的 Compose 项目名，也不要用全局 `docker compose down -v` 代替脚本的项目级清理。

### 3.2 直接调用数据库与上传恢复脚本

正常维护路径由 `Test-ComposeRuntime.ps1` 调用备份脚本。只有在已经准备好相互匹配的环境文件和 override 文件，并且明确接管清理责任时，才直接运行 `Test-ComposeBackup.ps1`：

```powershell
.\scripts\Test-ComposeBackup.ps1 `
  -SourceProject bluealbum-m0-a1b2c3d4e5 `
  -RestoreProject bluealbum-m0-a1b2c3d4e5-restore `
  -SourceEnvironmentFile .\outputs\m0-compose-a1b2c3d4e5\source.env `
  -RestoreEnvironmentFile .\outputs\m0-compose-a1b2c3d4e5\restore.env `
  -ComposeOverrideFile .\outputs\m0-compose-a1b2c3d4e5\compose.override.yml `
  -ArtifactDirectory .\outputs\m0-compose-a1b2c3d4e5\backup
```

六个参数全部必填：

- `-SourceProject` 和 `-RestoreProject` 必须符合 `bluealbum-m0-<10 位小写十六进制>`（恢复项目可带 `-restore`）且必须不同。
- `-SourceEnvironmentFile`、`-RestoreEnvironmentFile` 和 `-ComposeOverrideFile` 必须存在。两个环境文件的 `DB_NAME`、`NOTIFICATION_DB_NAME` 必须明确、合法、互不相同，并使用相同的数据库名称以便导入未修改的转储。
- `-ArtifactDirectory` 也必须位于仓库 `outputs` 目录下。

直接调用前，源项目的 `frontend`、`gateway`、`backend`、`notification-service` 必须已停止，但源项目的 `mysql`、`redis`、`rabbitmq` 必须存在且按脚本要求运行；恢复项目不能已有容器或卷。脚本会启动恢复项目的依赖、导入数据库、复制上传文件、再启动完整恢复栈。它不会执行 Compose `down`，不会删除项目、卷或源上传副本；因此不能把直接调用的输出误当作已清理完成的运行。

### 3.3 CI / 手动 Bash 浏览器冒烟

从仓库根目录运行：

```bash
bash scripts/ci/compose-browser-smoke.sh
```

脚本支持的环境变量如下；未设置时使用脚本默认值：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `M0_CI_ARTIFACT_DIR` | `outputs/m0-ci` | 必须位于仓库 `outputs` 目录下，不能直接等于 `outputs`。 |
| `COMPOSE_MYSQL_PORT` | `3306` | MySQL 主机端口。 |
| `COMPOSE_REDIS_PORT` | `6379` | Redis 主机端口。 |
| `COMPOSE_RABBITMQ_PORT` | `5672` | RabbitMQ 主机端口。 |
| `COMPOSE_RABBITMQ_MANAGEMENT_PORT` | `15672` | RabbitMQ 管理端口。 |
| `COMPOSE_BACKEND_PORT` | `8080` | 后端主机端口。 |
| `COMPOSE_NOTIFICATION_PORT` | `8081` | 通知服务主机端口。 |
| `COMPOSE_GATEWAY_PORT` | `9000` | Gateway 主机端口。 |
| `COMPOSE_FRONTEND_PORT` | `5173` | 前端主机端口。 |
| `GITHUB_RUN_ID`、`GITHUB_RUN_ATTEMPT` | CI 中由 GitHub 提供；手动运行可都不设置 | 两者只要有一个被设置就都必须是纯数字，并组成 `bluealbum-ci-<run>-<attempt>` 项目名；手动运行不设置时使用 UTC 时间戳和进程号。 |

例如在一台已有本地服务的机器上，把所有端口改到一组空闲值，并换一个产物目录：

```bash
M0_CI_ARTIFACT_DIR=outputs/m0-ci-manual \
COMPOSE_MYSQL_PORT=33306 \
COMPOSE_REDIS_PORT=36379 \
COMPOSE_RABBITMQ_PORT=35673 \
COMPOSE_RABBITMQ_MANAGEMENT_PORT=35674 \
COMPOSE_BACKEND_PORT=38080 \
COMPOSE_NOTIFICATION_PORT=38081 \
COMPOSE_GATEWAY_PORT=39090 \
COMPOSE_FRONTEND_PORT=35173 \
bash scripts/ci/compose-browser-smoke.sh
```

该入口固定把 `TEST_PLAYWRIGHT_CHANNEL` 设为空，因此使用 bundled Chromium；它创建的临时凭证文件权限为 `600`，退出时删除凭证文件，并只清理自己登记的 Compose 项目和卷。

## 4. 隔离、端口和凭证边界

### 本地完整入口

每次运行有两个 Compose 项目：

- 源项目：`bluealbum-m0-<RunId>`
- 恢复项目：`bluealbum-m0-<RunId>-restore`

脚本要求两个项目的容器和卷在开始时不存在，并在退出时按项目标签保存日志、执行 `down --volumes --remove-orphans`，再重新检查没有遗留容器和卷。它不会清理不属于这两个项目的 Docker 资源。

源项目和恢复项目使用下面两组固定宿主机端口；脚本在启动前逐一检查监听状态：

| 服务 | 源项目 | 恢复项目 |
| --- | ---: | ---: |
| MySQL | `33306` | `43306` |
| Redis | `36379` | `46379` |
| RabbitMQ | `35673` | `45673` |
| RabbitMQ Management | `35674` | `45674` |
| Backend | `38080` | `48080` |
| Notification | `38081` | `48081` |
| Gateway | `39090` | `49090` |
| Frontend | `35173` | `45173` |

浏览器测试访问源前端 `http://127.0.0.1:35173` 和源 Gateway API `http://127.0.0.1:39090/api/v1`；恢复读回访问恢复前端 `http://127.0.0.1:45173`。这些地址来自脚本，不应改写成共享本地栈地址。

脚本为每次运行生成 32 字节随机十六进制数据库、Redis、RabbitMQ、JWT 和内部服务凭证，并写入 `source.env` 与 `restore.env`。两个文件使用 `bluealbum_m0` 和 `bluealbum_m0_notifications` 数据库名；Compose 文件中的同名进程环境变量会在子脚本内暂时移除，脚本结束时恢复原值。正常结束时两个环境文件会删除；终止进程、磁盘权限或清理失败时可能残留，发现后必须按敏感凭证处理。

### 浏览器测试数据

浏览器旅程每次生成两个 UUID 后缀账号。账号密码、访问令牌和响应体不会写入测试产物；失败截图会先清空密码输入框、移除浏览器存储中的访问令牌和用户信息。为了让服务端审计读回可发生，测试会保留生成账号、通知和举报，并在有对应所有者操作时删除帖子、评论和收藏。完整 Compose 运行随后删除本次独立项目的卷，因此不能把测试账号当作生产账号，也不能在共享数据库运行该旅程。

## 5. 产物与敏感信息

本地完整入口的默认产物根目录是 `outputs/m0-compose`（或 `-ArtifactDirectory` 指定的 `outputs` 子目录）：

| 产物 | 内容与用途 |
| --- | --- |
| `compose.override.yml` | 本次 `RunId` 镜像标签以及脚本指定的 JVM 内存/处理器配置。 |
| `browser-results.xml` | 浏览器旅程的 JUnit 报告；完整入口要求至少有一个测试且没有 skipped、failure 或 error。 |
| `browser/fixture.json` | 浏览器旅程生成的账号、帖子、评论、举报和通知 ID；不含密码或令牌。 |
| `browser/m0-journey-failure-*.png` | 测试失败时的脱敏截图。 |
| `container-resources.jsonl` | 一次 `docker stats --no-stream` 采样。 |
| `host-memory-before.json` | 启动前物理内存和提交内存余量；4 GiB 提交余量门槛仅用于本机保护。 |
| `latency-samples.json` | 五次顺序的本地 API 样本，并写明不是容量基准。 |
| `fixture.json` | 备份恢复旅程使用的线程、账号和图片 URL ID 信息。 |
| `bluealbum-m0-<RunId>.log`、`bluealbum-m0-<RunId>-restore.log` | 清理前保存的 Compose 日志；对应的 `*-health.log` 保存容器健康状态。 |
| `checks.json` | 本地入口记录的检查项、`RunId`、`Succeeded` 和清理错误。只有在 `Succeeded` 为 `true` 时才表示入口自身的检查和清理都完成。 |
| `backup/m0-compose-backup-<内部 RunId>/` | 数据库转储、源/恢复上传目录、两个上传 SHA-256 清单和 `verification-summary.json`。 |
| `source.env`、`restore.env` | 运行期间的临时凭证文件；正常退出由脚本删除。 |

`database-dump.sql` 可能包含完整业务数据库数据；`source-uploads/` 和 `restore-uploads/` 可能包含用户上传内容；Compose 日志和环境文件也可能包含敏感信息。不要把这些文件提交 Git、上传到公共工件、贴入 issue 或复制到不受控位置。需要留档时只保留经过授权的最小报告，并按组织的密钥和数据保留规则处理。`verification-summary.json` 只记录校验摘要及哈希，不等于可以公开数据库转储。

CI 入口的默认产物是 `outputs/m0-ci`：`junit.xml`、失败截图、`logs/compose.log`、`logs/compose-down.log`，以及可能的 `logs/cleanup-error.log`。`ci-runtime.env` 是临时凭证文件，正常退出删除；若运行被强制终止，应先检查它是否残留，再按敏感文件处理。CI 脚本不会上传生成的环境文件。

## 6. 故障排查

### 本地 PowerShell 入口

- `RunId must contain exactly ten lowercase hexadecimal characters`：使用恰好 10 位 `[0-9a-f]`，例如 `a1b2c3d4e5`；不要使用大写、短横线或更长 UUID。
- `Compose verification artifacts must be under workspace outputs`：`-ArtifactDirectory` 必须是仓库 `outputs` 下的子目录，例如 `./outputs/m0-compose-next`。
- `Provide the Python executable with tests/requirements.txt installed`：确认 `-PythonExecutable` 指向存在的解释器，并在该解释器中安装 `tests/requirements.txt`。
- `This verification project already has resources; use a fresh RunId`：先用 Docker 标签检查精确的 `bluealbum-m0-<RunId>` 和 `...-restore` 项目；不要删除不属于本次运行的资源。若确认是上一次中断留下的本次项目，使用其环境文件和 override 做项目级清理，清理后再换新的 `RunId`。
- `Verification port ... is occupied`：查明占用端口的进程或容器并释放；本地完整入口没有端口覆盖参数，不能只改访问 URL。
- `M0 browser journey failed` 或 JUnit gate 报告 skipped/failure/error：查看 `browser-results.xml`、失败截图以及两个 Compose 日志。遇到“人工验证”表示外部验证码进入了测试边界，需在隔离栈关闭该外部验证后重试；不能把 skipped 当成通过。
- `NoBuild` 下找不到带 `RunId` 的镜像：移除 `-NoBuild` 让脚本构建，或者准备与该 `RunId` 完全相同的镜像标签。
- Compose health、构建或 Docker API 失败：先确认 Docker Engine、镜像构建依赖、端口和磁盘可用，再查看脚本保存的 `*-health.log` 和 `*.log`。不要用 HTTP 200 或前端页面出现作为完整栈健康和恢复通过的替代证据。
- 备份脚本提示源应用仍运行：停止源项目的 `frontend`、`gateway`、`backend`、`notification-service`；保持源 MySQL 运行以便取一致转储。备份期间不得有业务写入。
- 数据库快照不一致：确认源应用已停止、源与恢复环境文件使用相同数据库名，并查看 `verification-summary.json` 的失败阶段。`flyway_schema_history` 和 outbox 表只在允许应用启动变化的最终比较中按脚本规则排除；业务表不一致不能用该例外掩盖。
- 上传清单不一致：检查 `source-uploads.sha256.json`、`restore-uploads.sha256.json` 以及两份目录中的相对路径、文件大小和 SHA-256；不要手工改清单后再次运行。
- 清理失败：保留错误日志和项目名，按精确 Compose 项目标签复查容器/卷。完成项目级清理后才能复用 `RunId`；不要运行全局资源清理命令。

### CI / 手动 Bash 入口

- `openssl is required`：安装并确认 `openssl rand -hex 32` 可执行。
- `Compose project label already has resources`：脚本自动生成或由 CI 提供的项目名已存在容器/卷，先审查该精确项目，再重新运行。
- `Compose host port is occupied`：使用一组空闲端口覆盖全部需要的 `COMPOSE_*_PORT` 变量；脚本会逐项校验数值范围和宿主机端口占用。
- `temporary credential file already exists`：不要覆盖现有 `outputs/m0-ci/.../ci-runtime.env`；先确认它是否属于中断的本次运行并按敏感文件处理。
- `JUnit gate failed` 或没有 `junit.xml`：查看 `logs/compose.log`、失败截图和测试进程输出。该入口只验证浏览器旅程，不会自动转入本地完整备份恢复验收。

## 7. 验证记录边界

维护者完成一次运行后，应同时保存命令中的 `RunId`、产物目录和退出状态，并核对：

1. 浏览器 JUnit 至少包含一个实际执行的测试，且 skipped、failure、error 均为零。
2. 本地入口的 `checks.json` 中 `Succeeded` 为 `true`，清理错误为空；若使用直接备份入口，还要核对 `verification-summary.json` 的 `Status` 和完整 `DatabaseComparisons` / `UploadComparison`。
3. 恢复阶段的 API 读回和图片字节校验确实执行过；构建成功、Compose `up` 成功、HTTP 200 或页面可打开都不足以证明数据库和上传文件恢复正确。
4. 产物中的转储、上传副本、环境文件和日志已经按敏感信息边界处理。

CI 的 `compose-browser-smoke.sh` 只提供浏览器运行和清理证据；若验收要求包括重启、数据库/上传恢复、业务表哈希或容量判断，必须另外运行本地完整入口并以其实际产物为证。
