# BlueAlbum 自动化测试计划

`tests/` 是唯一的 Python 自动化测试根目录。

## 分层

- `tests/api/`：不启动浏览器的 HTTP API 契约和业务流程。
- `tests/e2e/selenium/`：Selenium Page Object 与核心 UI 流程。
- `tests/e2e/playwright/`：导航、移动视口、搜索、设置，以及收藏和举报审核的浏览器流程。
- Java 单元与集成测试放在各服务的 `src/test`；真实 MySQL 的联调需另行启动本地服务并记录结果，不能把单元测试通过等同于数据库联调通过。

## 本地运行

```text
python -m pytest -c tests/pytest.ini --collect-only -q -p no:cacheprovider tests
python -m pytest -c tests/pytest.ini tests/api -m api
python -m pytest -c tests/pytest.ini tests/e2e/selenium -m ui --selenium-browser=chrome
python -m pytest -c tests/pytest.ini tests/e2e/playwright -m ui
```

测试账号、服务地址和浏览器参数通过被忽略的 `tests/.env`、进程环境变量或 CI secret 注入；不要提交实际凭证。Selenium 使用 Selenium Manager，不提交浏览器驱动或下载器缓存。测试报告、截图、缓存和字节码均为本地生成物，不属于源码。API/UI 用例会创建或修改数据，只能在隔离的本地／测试环境运行。

## 验收重点

1. 未认证请求不能访问写接口、通知或 AI。
2. 用户只能修改、删除和读取自己拥有的资源。
3. QR/OAuth 一次性凭证过期后不可重放，URL 不出现 JWT。
4. 内容渲染对脚本、事件属性和危险 URL 进行清理。
5. 真实服务链路必须经过 Gateway；Mock 只能在显式 local/test 配置中启用。
6. 收藏按账号持久化，隐藏内容不会继续出现在公开详情、评论列表或收藏列表。
7. 仅配置的现有管理员可以处理举报；普通用户访问管理接口应被拒绝，403 不应使前端退出登录。

以上是验收目标，不代表 CI 已执行所有真实服务和浏览器场景。当前 CI 运行 Java、前端检查，并收集 Python 用例；API/UI 执行结果应单独记录。
