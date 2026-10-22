# 脚本索引

从仓库根目录执行。先区分本机运行状态和可销毁的验收环境；脚本名称中有 `Test` 不代表只读，也不代表无需依赖服务。

## 本机开发

| 入口 | 作用与边界 |
| --- | --- |
| `Start-Local.ps1` / `Stop-Local.ps1` | 管理当前工作区本地栈；进程、凭据和日志属于 `test-artifacts/local-runtime/`，不要移动活动状态 |
| `Test-Local.ps1` | 本地接口冒烟，会创建账号、帖子、评论等数据 |
| `database/` | 数据库辅助入口；运行前核对实际目标，不能当作版本迁移替代品 |
| `init-notification-db.sh` | Compose 初始化通知逻辑库，属于数据库启动流程 |

## 隔离运行验收

| 入口 | 范围 |
| --- | --- |
| `Test-ComposeRuntime.ps1` | 完整 Compose、API/浏览器、重启和独立恢复；`-IncludeStageFixtures` 增加权限、摘要与通知夹具 |
| `Test-ComposeBackup.ps1` | 停止源业务写入后比较两库与上传文件，恢复到独立项目 |
| `Test-ContentEventRuntime.ps1` | 真实 MySQL 内容事件事务与升级 |
| `Test-NotificationRuntime.ps1` | 独立通知数据库、投递、幂等、隔离与恢复 |
| `Test-PhoneUniqueness.ps1` | 手机号约束和并发注册专项，参数及要求见工程手册 |
| `Test-PerfServices.ps1` | 性能实验依赖健康检查，不代表容量压测达标 |
| `Test-ExternalSummary.ps1` | 显式真实模型验收，使用私有配置并可能产生费用；不属于默认离线回归 |

`ci/` 包含 Nginx 地址恢复、内容搜索、社区治理、Go 媒体/SSE、受控摘要及浏览器回归。完整 Python 回归与原 mandatory16 分别执行，具体准备条件见[测试说明](../tests/README.md)和[CI 配置](../.github/workflows/ci.yml)。

## 不依赖业务服务的检查

```powershell
node scripts/Check-Repository.mjs
node scripts/Check-JavaArchitecture.mjs
node --test scripts/__tests__/*.test.mjs
python -m unittest discover -s scripts/ci -p 'test_*.py' -v
```

Node 工程测试中的 shell 用例需要 Bash；Windows 可通过 `BASH_BIN` 指向 Git Bash。不要把 shell 启动失败记成业务用例失败或跳过。PowerShell 源码还需进行语法解析。

验收产物写入被忽略的 `outputs/`，凭据只用于本次独占环境。失败日志、JUnit、清理结果均应保留；只清理本次登记且确认归属的资源。`__tests__/` 是工程脚本测试，`ci/test_*.py` 是运行证据门禁的离线测试，二者不能代替真实数据库或浏览器操作。

目录规则见[文件归属](../docs/engineering/repository-layout.md)，运行与恢复步骤从[工程文档索引](../docs/engineering/README.md)进入。
