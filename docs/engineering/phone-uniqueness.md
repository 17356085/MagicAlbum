# 手机号唯一性与迁移验证

`users.phone` 是普通注册、账号基本资料更新和手机号登录使用的业务标识。`User` 实体已经声明 `phone` 非空且唯一；Flyway 历史迁移 `V2__create_users.sql` 只创建了用户名和邮箱唯一键，因此 `V20261017__add_users_phone_unique.sql` 为现有 MySQL 库补上名为 `users_phone_uq` 的唯一键。实体上的 `@UniqueConstraint` 没有名称属性，唯一键名称由迁移显式指定。

## 迁移边界

迁移只执行以下 DDL：

```sql
ALTER TABLE users
    ADD CONSTRAINT users_phone_uq UNIQUE KEY (phone);
```

迁移不会删除、合并或改写账号。旧库存在重复手机号时，MySQL 会拒绝这条 `ALTER TABLE`，Flyway 会把迁移标记为失败并阻止应用启动。管理员需要先人工处理重复账号，再重新运行迁移；处理过程必须保留账号归属和审计记录。

迁移前使用只读预检，结果只包含重复手机号分组数量：

```sql
SELECT COUNT(*) AS duplicate_phone_groups
FROM (
    SELECT phone
    FROM users
    GROUP BY phone
    HAVING COUNT(*) > 1
) duplicates;
```

不要把 `phone` 放进预检结果、日志或工单附件。`duplicate_phone_groups = 0` 才能进入迁移窗口；该查询只是上线前检查，最终约束仍由 MySQL 负责并发安全。

## 注册、更新与竞态响应

`UserService` 会先做可用性检查，手机号已存在时返回 `409` 和固定消息“该手机号已被占用”。这个检查不能解决两个请求同时通过检查的竞态；真正写入由 `users_phone_uq` 串行裁决，失败请求抛出 `DataIntegrityViolationException`，由全局处理器返回同一冲突响应。

全局异常处理只根据约束名映射固定业务消息：

```json
{"status":409,"message":"该手机号已被占用"}
```

响应不会包含 SQL、约束详情或手机号，全局异常处理器只记录固定业务消息；这不保证 Hibernate、JDBC 等底层日志也已完成脱敏，部署时应独立核对日志策略。注册 `POST /api/v1/users/register` 与资料更新 `PATCH /api/v1/users/me/basic` 共用这条映射。

OAuth 新账号不要求真实手机号，`OAuthUserProvisioningService` 用 `199` 前缀的合成号码，并在写入前循环避开已存在值；该逻辑与手机号唯一键兼容。OAuth 关联已有账号不会改写原账号手机号。

## 隔离验收

先运行定向 Java 回归：

```powershell
mvn -pl backend -am '-DskipTests=false' '-Dtest=UserServicePhoneConflictTest,GlobalExceptionHandlerPhoneConflictTest,UserPhoneConflictControllerTest' test
```

`scripts/Test-PhoneUniqueness.ps1` 会创建带 `bluealbum-phone-unique-<RunId>` 标记的临时 MySQL 8 容器，不添加外部卷，也不连接现有 Compose 项目；MySQL 镜像默认创建的匿名数据卷会随该精确容器一并清理。它按场景验证：

```powershell
.\scripts\Test-PhoneUniqueness.ps1 -Scenario All
```

脚本包含四组检查：新库迁移成功；无重复旧库迁移成功；重复旧库预检只记录数量且迁移被阻断、数据行保持不变；两个独立 MySQL 连接并发写入同一手机号时一写成功、一写失败且最终只保留一行。脚本将结果写入 `outputs/phone-uniqueness/<RunId>/checks.json`，并在 `finally` 中只删除自己创建的精确容器名。

该脚本的并发场景验证数据库唯一键。HTTP 409 的稳定响应由上述 Java 回归覆盖；如需完整应用级验收，应在独立数据库和独立应用进程上运行注册、登录、更新及数据库读回，禁止指向正在使用的用户库或本地运行栈。
