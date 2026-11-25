# 同频 Demo 演练

从受测提交的仓库根目录运行。准备 Docker Compose v2、Git、Bash 和 Python 3.12 或更新版本。Windows 可在 Git Bash 或已接入 Docker Desktop 的 WSL 中运行。首次构建需要下载镜像与项目依赖。

## 现场演示

```bash
BLUEALBUM_DEMO_MODE=interactive bash scripts/ci/compose-browser-smoke.sh
```

脚本创建专用 Compose 项目、新数据库卷和合成账号。默认演示地址为 `http://127.0.0.1:15173`，其它演示端口为 13306、16379、15673、15674、18080、18081 和 19000。发现端口或项目已占用时退出。需要调整时，先选择未占用的端口，再通过 `COMPOSE_*_PORT` 变量指定。脚本就绪后会显示页面地址和本地凭证文件路径。浏览器可通过独立窗口分别登录作者、读者和管理员。

1. 从读者账号打开关注动态，核对种子主题与评论，再发起一次评论或点赞。从作者账号读回对应互动和站内通知。
2. 用两个普通账号完成好友申请，发送私信，再建立群聊并验证消息读取。
3. 从普通账号提交举报，从管理员账号审核，再读取主题状态及审计记录。
4. 按回车结束演示。脚本清理本次项目、数据卷和私有凭证，保留脱敏验收产物。

要演示独立聊天数据库，先结束上次演示，再加 `BLUEALBUM_SPLIT_CHAT_CHECK=1` 重启同一流程。演示使用本地认证和 Mock AI，全部端口绑定在本机。凭证文件仅供当前会话使用。

## 自动彩排

```bash
python -m pip install -r tests/requirements.txt
python -m playwright install chromium
BLUEALBUM_DEMO_MODE=rehearsal bash scripts/ci/compose-browser-smoke.sh
BLUEALBUM_DEMO_MODE=rehearsal BLUEALBUM_SPLIT_CHAT_CHECK=1 bash scripts/ci/compose-browser-smoke.sh
```

Linux 如缺少浏览器系统依赖，可用 `python -m playwright install --with-deps chromium`。自动彩排初始化并读回合成数据，再运行真实浏览器旅程。输出目录含 `source-revision.json`、脱敏读回 JSON、浏览器 JUnit 和清理结果。CI 只上传这些公开验收产物。

独立聊天切换和回退、跨库权限、数据库和上传文件恢复均有单独门禁。[聊天分库与迁移](../architecture/03-数据库归属与迁移.md) · [恢复验收](m0-runtime-verification.md) · [CI 配置](../../.github/workflows/ci.yml)

## 历史数据库备份

在包含 Git 历史的检出目录中，用 PowerShell 7.4、Java 21、Maven 和 Docker 运行。

```powershell
./scripts/Test-ContentEventRuntime.ps1 -DemoHistory -MavenExecutable mvn
```

门禁使用旧主分支 `a6fa06f` 的 MySQL 迁移基线和合成数据。它在隔离数据库创建原始备份，将备份还原到候选库并测试升级，然后把原始备份独立还原以演练回退。输出记录新旧提交、Flyway 版本、备份摘要、验收结果和容器清理情况。应用层的双拓扑、实际账号权限、上传恢复和聊天回退仍由各自门禁执行。
