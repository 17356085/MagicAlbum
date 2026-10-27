# MySQL JDBC 时区配置手册

本手册说明 BlueAlbum 当前的 MySQL UTC 连接约定，以及显式自定义 URL 时由部署者承担的配置责任。它适用于论坛核心 `backend` 和独立 `notification-service`。

> 显式完整 URL 覆盖不会自动补回默认参数；覆盖者必须显式保留 `serverTimezone=UTC&forceConnectionTimeZoneToSession=true`。

## 1. 当前项目契约

默认配置中以下路径都保留这两个 JDBC 参数：

- `backend/src/main/resources/application.yml`、`application-mysql.yml` 和 `application-local.yml` 的 `spring.datasource.url`。
- `notification-service/src/main/resources/application.yml` 的缺省 URL，以及根 `docker-compose.yml` 中通知服务的默认 URL。
- 当前通知运行验证、内容事件和内容搜索专项脚本生成的 JDBC URL。

`serverTimezone=UTC` 是 Connector/J 的时区转换声明；`forceConnectionTimeZoneToSession=true` 要求连接初始化时把该 JDBC 会话设置为 UTC。两者是当前项目默认组合。默认路径不需要重复修复。

当前仓库没有 `MYSQL_URL` 或 `DB_URL` 的消费者。不要把它们宣传为 `DB_*` 或 Spring datasource URL 的别名；需要覆盖时必须使用对应服务真正读取的入口。

## 2. 直接运行 Spring/JAR 时的显式 URL

自定义 URL 是整条 URL 替换，不是只替换主机名后由项目自动补参数。示例使用无凭据的占位主机：

下例中的 `backend.jar` 和 `notification-service.jar` 也是占位文件名，请替换为已构建的实际 JAR 路径；完整启动步骤见[项目 README](../../README.md)。

```text
jdbc:mysql://db.example.test:3306/MagicAlbum?serverTimezone=UTC&forceConnectionTimeZoneToSession=true
```

### backend

直接运行核心 JAR 时，可以使用标准 Spring 属性或其环境变量映射：

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://db.example.test:3306/MagicAlbum?serverTimezone=UTC&forceConnectionTimeZoneToSession=true"
java -jar backend.jar

# 或者只对本次进程传入命令行属性
java -jar backend.jar "--spring.datasource.url=jdbc:mysql://db.example.test:3306/MagicAlbum?serverTimezone=UTC&forceConnectionTimeZoneToSession=true"
```

`SPRING_DATASOURCE_URL` 和 `--spring.datasource.url` 都是完整覆盖入口。项目不会把自定义值再拼接成默认 URL；覆盖者必须自己保留 `serverTimezone=UTC` 与 `forceConnectionTimeZoneToSession=true`。

### notification-service

通知服务的配置使用它自己的全量 URL 变量：

```powershell
$env:NOTIFICATION_DATASOURCE_URL = "jdbc:mysql://db.example.test:3306/MagicAlbum_notifications?serverTimezone=UTC&forceConnectionTimeZoneToSession=true"
java -jar notification-service.jar
```

不要只设置 `SPRING_DATASOURCE_URL` 来替代 `NOTIFICATION_DATASOURCE_URL`；通知服务的 `application.yml` 明确读取后者。

### 与 `scripts/Start-Local.ps1` 的边界

直接运行 JAR 的覆盖入口不等于仓库启动脚本会自动透传。`scripts/Start-Local.ps1` 的标准本地运行配置由私有 `test-artifacts/local-runtime/runtime.env` 中的 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD` 和 `NOTIFICATION_DB_NAME` 等变量组成；脚本启动 JAR 时只追加地址、端口和本地上传目录参数，没有自动追加 `--spring.datasource.url`。

脚本会先隔离调用进程中继承的 `SPRING_DATASOURCE_*`、`NOTIFICATION_*` 等变量，再加载 `runtime.env`。因此，仅在启动 PowerShell 会话中设置 `SPRING_DATASOURCE_URL` 或 `NOTIFICATION_DATASOURCE_URL`，不能据此声称它会改变 `Start-Local.ps1` 的连接 URL。脚本解析器允许大写扩展键；只有把 URL 键明确写进私有 `runtime.env` 才属于手工显式覆盖，仍须使用完整 UTC URL，并且不得提交或分享该文件。

## 3. 完整 Compose 的边界

根目录 [`.env.example`](../../.env.example) 是“完整 Compose 本地运行”模板。复制出的 `.env` 只是 Compose 的变量替换来源；变量不会因为出现在 `.env` 中就自动进入容器。只有当前 Compose 配置中对应服务的显式 `environment:` 或 `env_file:` 映射，才会传给该容器；本仓库当前使用 `environment:`。

当前 Compose 默认路径使用 backend 的 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`，并在通知服务中显式设置 `NOTIFICATION_DATASOURCE_URL`。不要把未被显式 `environment:` 或 `env_file:` 映射的 `SPRING_DATASOURCE_URL` 或 `NOTIFICATION_DATASOURCE_URL` 添加到根 `.env`，然后把它们当作已经生效。

一般部署继续使用现有 `DB_*` 配置即可。确实需要把完整 URL 传给 Compose 服务时，可用不提交的 Compose override，明确映射到对应服务；不要只把变量写进 `.env`。例如：

```yaml
# docker-compose.override.yml 示例；仅供本机使用，不要提交真实凭据
services:
  backend:
    environment:
      SPRING_DATASOURCE_URL: "jdbc:mysql://db.example.test:3306/MagicAlbum?serverTimezone=UTC&forceConnectionTimeZoneToSession=true"
  notification-service:
    environment:
      NOTIFICATION_DATASOURCE_URL: "jdbc:mysql://db.example.test:3306/MagicAlbum_notifications?serverTimezone=UTC&forceConnectionTimeZoneToSession=true"
```

override 中的 URL 仍是整条覆盖；`.env` 中未映射的同名变量不会替你完成这一步。示例不含账号、密码或真实主机；连接凭据、TLS 和其他驱动开关应按实际部署配置。

## 4. 外部配置与 Hikari 属性

Nacos、挂载的外部配置、`SPRING_APPLICATION_JSON` 以及 Hikari 的 datasource 属性都可能成为显式覆盖通道。当前批次没有启动完整应用来验证所有 Spring property source 的全优先级，因此本手册不画绝对的全序；请在实际服务上核对最终生效的 URL 和 driver 属性。

如果通过 `spring.datasource.hikari.data-source-properties.*` 或等价 Hikari 配置设置 Connector/J 属性，必须与项目 UTC 契约一致，至少不要把 `forceConnectionTimeZoneToSession` 设为 `false`，也不要配置相互冲突的非 UTC 时区。例如：

```properties
spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession=true
```

若使用 Connector/J 的 `connectionTimeZone`，应结合实际 Connector/J 版本和同一个应用 `DataSource` 做真实连接读回验证；不要仅凭配置解析结果把它宣传为已经验证的替代方案。

## 5. 不能互相替代的时区设置

- `serverTimezone=UTC` 和 `forceConnectionTimeZoneToSession=true` 属于 JDBC URL/driver 与连接会话层。
- `hibernate.jdbc.time_zone=UTC` 只约束 backend 的 Hibernate/JPA 时间绑定。
- `spring.jackson.time-zone=UTC` 只约束 backend JSON 序列化配置。

后两项不能改写自定义 JDBC URL，也不能替 notification-service 的手写 JDBC 连接设置。它们不能替代同一连接会话上的 UTC 配置。

## 6. 验证要求与证据边界

静态检查可以运行：

```powershell
node scripts/Check-Repository.mjs
```

该检查用于仓库结构、包声明、本地导入和文档链接，不证明自定义 URL 已经建立连接。

真实验收必须从**应用同一个 JDBC `DataSource`/连接**读回会话时区，例如在该服务取得连接后执行：

```sql
SELECT @@session.time_zone;
```

读回值应为 UTC 语义（MySQL 常见返回 `+00:00` 或 `UTC`），并应在应用连接上进行必要的时间戳读写往返。管理终端或另一个 `mysql` 命令行新连接的 `@@session.time_zone` 不能替代应用连接证据，因为它们可能是不同会话。

本批次只完成配置/源码探针和文档修正，没有新增真实 SQL、生产部署或完整应用启动验收。历史隔离 MySQL 记录可参考[工程审查记录](./optimization-review-2026-10-23.md)及其引用的专项脚本，但不能据此宣称任意自定义 URL 或生产环境已经验收。

不要把真实 `.env`、`runtime.env`、账号、密码或令牌复制到文档、日志或 issue。
