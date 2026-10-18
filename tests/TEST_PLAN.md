# BlueAlbum 自动化测试计划

`tests/` 是唯一的 Python 自动化测试根目录。

## 分层

- `tests/api/`：不启动浏览器的 HTTP API 契约和业务流程。
- `tests/e2e/selenium/`：Selenium Page Object 与核心 UI 流程；`test_ci_smoke.py` 使用独立种子进入 mandatory CI，其他历史用例仍作为需要外部账号和历史数据的回归套件运行。
- `tests/e2e/playwright/`：导航、移动视口、搜索、设置，以及收藏和举报审核的浏览器流程。
- `tests/e2e/playwright/test_m0_journey.py`：面向独立 Compose 栈的 M0/R01/R04 串行验收旅程；创建两个 UUID 测试账号，A 走浏览器注册/登录，B 走 API 注册后进入第二个浏览器上下文。
- `tests/api/test_ci_contract.py`、`test_bookmarks.py`、`test_comments.py`、`test_threads.py`、`test_user.py`：面向独立 Compose 栈的 mandatory API 契约组；共享两个 UUID 测试账号、一条帖子和一条评论种子，覆盖持久化、交互、隔离、匿名写入、关注和并发手机号冲突验证。
- `tests/e2e/selenium/test_ci_smoke.py`：面向独立 Compose 栈的 mandatory Selenium 健康流程；API 创建账号、帖子和评论，Selenium 读取帖子并提交评论，再由 API 重读评论。
- Java 单元与集成测试放在各服务的 `src/test`；真实 MySQL 的联调需另行启动本地服务并记录结果，不能把单元测试通过等同于数据库联调通过。

## 本地运行

```text
python -m pytest --collect-only -q -p no:cacheprovider tests
python -m pytest tests/api/test_ci_contract.py tests/api/test_bookmarks.py tests/api/test_comments.py tests/api/test_threads.py tests/api/test_user.py -m api
python -m pytest tests/e2e/selenium/test_ci_smoke.py -m ui --selenium-browser=chrome
python -m pytest tests/e2e/selenium -m ui --selenium-browser=chrome
python -m pytest tests/e2e/playwright -m ui
python -m pytest --collect-only -q -p no:cacheprovider tests/e2e/playwright/test_m0_journey.py
```

独立 Compose 栈中的 M0/R01/R04 浏览器验收命令（从仓库根目录执行）：

```text
TEST_BASE_URL=http://127.0.0.1:35173 \
TEST_API_BASE_URL=http://127.0.0.1:39090/api/v1 \
TEST_PLAYWRIGHT_CHANNEL=chrome \
TEST_ARTIFACT_DIR=outputs/m0-test \
python -m pytest -q tests/e2e/playwright/test_m0_journey.py
```

该用例不使用 `tests/conftest.py` 中的既有账号 fixture，而是自行创建两
个 UUID 账号并用 `secrets` 生成密码；密码和 token 不打印、不写入
artifact。A 通过真实注册/登录 UI，B 用 API 注册后注入第二个浏览器上下
文。发帖、评论、收藏/取消收藏、通知查看/已读、举报提交都经过浏览器
操作，并通过 API 重读持久化结果；两个账号的反向修改/删除权限也通过
API 验证。若 UI 开启外部人工验证，mandatory 流程会失败并报告隔离栈契约
不满足，不会将未执行流程计入通过。

成功运行会在 `TEST_ARTIFACT_DIR/fixture.json` 写入不含敏感信息的
`ownerId`、`readerId`、`threadId`、`postId`、`reportId` 以及两个通知 ID，
供主 runner 做 API/SQL 重读。测试会保留账号、举报和通知以便审计，并在
可用的 owner API 上清理收藏、评论和帖子（帖子软删除、评论删除）；因此
只能在可丢弃的隔离数据库执行。

测试账号、服务地址和浏览器参数通过被忽略的 `tests/.env`、进程环境变量或 CI secret 注入；不要提交实际凭证。Selenium 使用 Selenium Manager，不提交浏览器驱动或下载器缓存。测试报告、截图、缓存和字节码均为本地生成物，不属于源码。API/UI 用例会创建或修改数据，只能在隔离的本地／测试环境运行。

## 验收重点

1. 未认证请求不能访问写接口、通知或 AI。
2. 用户只能修改、删除和读取自己拥有的资源。
3. QR/OAuth 一次性凭证过期后不可重放，URL 不出现 JWT。
4. 内容渲染对脚本、事件属性和危险 URL 进行清理。
5. 真实论坛验收链路必须经过 Gateway；Mock 只能用于明确隔离的本地或测试配置，独立 perf 核心请求实验不能替代完整论坛验收。
6. 收藏按账号持久化，隐藏内容不会继续出现在公开详情、评论列表或收藏列表。
7. 仅配置的现有管理员可以处理举报；普通用户访问管理接口应被拒绝，403 不应使前端退出登录。

以上是验收目标，不代表 CI 已执行所有真实服务和浏览器场景。CI 配置包含 Java、前端与 Python 收集检查，并在隔离容器任务中顺序执行 mandatory API、Selenium 和 Playwright；实际执行状态以各自 JUnit 报告和工作流结果为准。
