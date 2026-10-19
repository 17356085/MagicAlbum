# M1 模块契约与跨域依赖基线

更新时间：2026-10-18

本文固定 M1 阶段的同应用模块边界。它描述当前实现、当前允许的历史耦合和新代码的检查规则，不把目录拆分称为微服务，也不要求在 M1 一次性迁移所有历史调用。

## 1. 规则与检查范围

### 1.1 域归属

| 域 | 当前 Java 包根 | 权威数据或职责 |
| --- | --- | --- |
| identity | `user`、`auth` | 用户、资料、关注、通知偏好、连接账号和认证生命周期 |
| content | `threads`、`posts`、`sections`、`tags` | 帖子、评论、分区、标签、内容可见性及帖子/评论点赞 |
| interaction | `bookmarks` | 收藏关系和收藏列表 |
| notifications | `notifications` | core 通知 Outbox 以及独立 notification-service 的收件箱、已读、去重 |
| storage | `storage` | 图片校验、存储 provider、资源归属、媒体任务与请求 Outbox、租约及派生对象清理 |
| ai | `ai` | AI 调用、摘要缓存和用量；帖子摘要状态仍由 content 的帖子记录承载 |
| governance | `moderation`、`admin`、`governance` | 持久化角色、版主范围、发言限制、分区管理及审计；隐藏与恢复由内容 owner 执行 |
| infrastructure | `common`、`config`、`bootstrap` | 配置、基础设施适配、Redis/RabbitMQ wiring 和本地种子数据 |

上述映射存放在 [`config/java-domain-couplings.json`](../../config/java-domain-couplings.json)。未登记的 backend 包根按自身名称视为独立域，不能通过遗漏配置绕过检查。

### 1.2 新代码规则

- `entity` 和 `repo` 是受保护层。不同域之间新增 Entity/Repository 源码依赖会失败。
- 历史引用按 **相对源文件路径 + 完整目标类型** 精确白名单保留。当前基线有 43 对历史引用；同一目标换一个源文件仍会失败。
- 跨域 DTO、公开 Service/Client 入口和基础 Java 类型可以继续使用；原有 DTO/Entity/Repository/Service 层级规则仍然有效。
- 跨域写操作必须进入数据 owner 的 Service，由 owner 负责事务、授权和可见性校验。审核域读取内容的历史耦合保留；隐藏调用内容 owner，未来恢复命令也必须遵守同一边界。
- M2 的 `ThreadContentEvent` 放在 `threads.event`，属于 content 域；它使用 content Entity 不需要白名单。未来 search 模块只能使用 content owner 的 DTO/Service 入口，不得导入 `threads.entity` 或 `threads.repo`。

### 1.3 检查边界

`node scripts/Check-JavaArchitecture.mjs` 检查 Java 源码中的 imports 和显式全限定应用类型引用，并在匹配前移除注释和字符串。它不分析 JPQL/SQL 字符串、反射、运行时依赖注入、Spring 装配或实际授权执行结果；检查通过不能代替集成测试和数据库读回。

现有三应用之间的 Java 直接依赖、DTO/Entity/Repository/Service 层级依赖仍按原规则拒绝。域规则只针对 backend 内不同域的 `entity`/`repo` 目标。

## 2. 当前接口与数据归属

| 域 | 当前对外入口和 DTO/Service | 事务边界 | 授权入口 | 已知历史债务 |
| --- | --- | --- | --- | --- |
| identity | `/api/v1/users/**`、登录/OAuth/二维码接口；`UserDto`、`UserSummaryDto`、设置和关注 DTO；认证 Service | 关注新增/取消由 `UserFollowService` 事务包住；用户注册、基本资料和密码修改目前没有 Service 级显式事务声明，需单独补充针对性验证 | Controller 从 `SecurityContext` 取得当前用户；owner Service 校验目标用户和关系 | auth 读取 user Entity/Repository 属于同一 identity 域；不要把这类读取扩展到其他域 |
| content | `/api/v1/threads/**`、`/api/v1/threads/{threadId}/posts`；`ThreadService`、`PostService`；线程/帖子查询 DTO 和分页投影 | 发帖、编辑、删除、隐藏、评论和点赞写入由 content Service 的事务负责；通知 Outbox 写入必须加入调用事务 | 线程作者、评论作者和当前登录用户由 owner Service 检查；审核隐藏通过 content owner | `ThreadService` 读取 user/section，`PostService` 读取 user/thread；这些历史 Entity/Repository 对在配置中逐对保留 |
| interaction | `/api/v1/threads/{id}/bookmark`、`/api/v1/users/me/bookmarks`；`ThreadBookmarkService`、收藏状态/列表 DTO | 收藏取消和列表显式事务；收藏新增依靠复合唯一键处理并发，当前缺少 Service 级显式事务声明 | 入口使用当前登录用户；目标帖子可见性由 content owner 数据校验 | 收藏 Service 和 Repository 读取 thread Entity/Repository，精确白名单保留；新的收藏跨域写仍应经 content owner |
| notifications | core `NotificationClient`/`NotificationService`/`NotificationOutbox`；独立服务 `/api/v1/notifications` 和内部创建接口 | core Outbox `enqueue` 要求调用方已有事务；消费端持久化后才确认；独立服务以 `eventId` 唯一键幂等 | 用户通知查询按当前用户；内部接口使用独立 service token | core 客户端读取 user 通知偏好是公开 Service 入口；通知 Outbox 不能伪装成 M2 内容事件 |
| storage | 上传、owner 状态/取消/重试及私有 worker 接口；`MediaAssetStatus` | 上传意图、确认/失败、任务领取/候选/接受各自短事务；对象写入在事务外，任务与请求 Outbox 同事务 | 用户 JWT 只授权 owner API；独立服务令牌与当前租约约束 worker；默认关闭媒体登记/处理 | 旧 URL 保持原语义，不自动迁移历史对象；新状态只在启用登记后建立，见 M3 契约 |
| ai | `/api/v1/ai/**`；`AiService`、摘要缓存和 RabbitMQ 摘要 listener | 帖子更新把摘要状态置为 `PENDING`；listener 使用租约和条件更新避免旧结果覆盖 | 摘要读取应遵守 content 当前可见性；现有 controller 仍有直接读取 Thread 的历史对 | AI controller 读取 thread Entity/Repository 已在精确白名单；M2/M4 新调用应改为 content owner DTO |
| governance | 举报、`/api/v1/governance/**`；`ContentReportService`、`GovernanceService`、`AdminAccessService` | 当前数据库角色与范围决定权限；角色/限制/分区管理及审计同事务提交；隐藏/恢复由 content owner 执行 | 版主继承父线程当前分区；作者写入检查发言限制与分区归档；管理员引导只执行一次 | M4A 为用户与分区行锁、分区配置保存增加五条精确依赖，理由和锁顺序见 M4A 记录；不允许治理直接写帖子/评论表 |
| infrastructure | 本地种子、配置、公共 Redis/RabbitMQ 适配 | 不拥有业务事务；只参与被调用 owner 的事务 | 不绕过 owner 授权 | 本地种子直接读写多个域 Repository 是启动夹具，精确白名单保留；新增业务代码不得仿效 |

## 3. M2 内容事件边界

内容主数据仍由 content 域的 MySQL 表权威持有。内容事件应在内容事务内写入独立的 Content Event Outbox，与通知 Outbox 分开。事件至少需要固定 `eventId`、`eventType`、`schemaVersion`、`aggregateId`、`aggregateVersion/contentVersion`、发生时间、关联 ID 和受控 payload。

`NORMAL`、`HIDDEN`、`DELETED` 的状态变化必须通过版本或墓碑语义阻止旧事件重放恢复隐藏内容。消费者以稳定 document ID 和版本规则幂等处理；重放和乱序不能覆盖更新版本。索引重建应包含基线、增量补齐、版本校验、别名切换和旧索引回退。搜索不可用时只能按明确能力差异降级到权威数据库，并记录降级指标。

M2 search 模块的数据读取只能调用 content owner 的 DTO/Service 入口，不得直接读取或写入 `threads.entity`、`threads.repo`、`posts.entity` 或 `posts.repo`。`threads.event` 内的 `ThreadContentEvent` 是 content owner 的内部消息模型，不需要跨域白名单。

## 4. 历史耦合的处理方式

M1 只冻结上述规则和基线，不在一次变更中迁移 43 对历史 Entity/Repository 引用。后续每次迁移应先增加 owner DTO/Service 契约和行为测试，再删除对应白名单条目；删除条目后由架构检查证明旧依赖没有回流。跨域字符串查询、反射和运行时装配仍需通过对应集成测试验证，不能把静态检查结果当作这些行为的证明。

## 5. M3 跨语言与通知边界

Go 只消费已登记任务，通过 Java 私有 API 领取、续租、读取确定输入及回传；不直接读写媒体表，不持有 S3 凭据。任务版本、租约散列、候选和输出由 storage 域持有，结果接受后才确认消息；重投使用原事件，用户重试另建版本。清理只能处理派生位置，不删除原图。完整字段及重试语义见[执行契约](m3-runtime-contract.md)。

通知服务持有自己的 owner 事件日志及串行游标，通知创建/已读与事件同事务提交。SSE 只发送变化标识，普通通知列表仍是内容读取入口；不跨账号复用连接和游标。媒体状态由 storage owner API 查询，不借用通知表充当媒体任务主数据。当前实现和运行验收结果按[阶段记录](stage-progress.md)分别确认。
