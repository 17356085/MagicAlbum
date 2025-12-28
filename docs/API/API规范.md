# MagicAlbum API 规范（初稿）

版本：v0.1 初稿  
状态：草案  
更新日期：2025-12-21

## 0. 通用约定
- Base URL：`/api/v1`
- 认证：`Authorization: Bearer <token>`（登录接口返回）。
- 分页：`page`（默认 1）、`size`（默认 20）、`sort`（如 `created_at,desc`）。
- 查询：`q` 为关键字，作用于名称/标题/描述。
- 错误响应：
```json
{ "message": "错误提示", "code": "ERR_CODE", "details": {"field": "..."} }
```

## 1. 鉴权 Auth
- POST `/auth/register`
  - Req: `{ username, email, password }`
  - Res: `{ id, username, email }`
- POST `/auth/login`
  - Req: `{ usernameOrEmail, password }`
  - Res: `{ accessToken, tokenType: "Bearer", expiresIn }`
- GET `/auth/profile`
  - Res: `{ id, username, email, role }`

## 2. 分区 Sections
- GET `/sections`
  - Query: `q?`, `page?`, `size?`, `sort?`
  - Res: `Section[]` 或 `{ items: Section[], page, size, total }`
- GET `/sections/{id}` / `/sections/slug/{slug}`
  - Res: `Section`
- POST `/sections`（管理员）
  - Req: `{ name, slug, description, visible? }`
  - Res: `Section`

`Section` 示例：
```json
{ "id":1, "name":"摄影", "slug":"photo", "description":"摄影作品交流", "visible":true }
```

## 3. 帖子 Threads
- GET `/threads`
  - Query: `q?`, `page?`, `size?`, `sectionId?`, `sort?`
  - Res: `{ items: Thread[], page, size, total }`
- GET `/sections/{id}/threads`
  - Query: `q?`, `page?`, `size?`, `sort?`
  - Res: `{ items: Thread[], page, size, total }`
- GET `/threads/{id}`
  - Res: `Thread`
- POST `/threads`（需登录）
  - Req: `{ sectionId, title, content }`
  - Res: `Thread`

`Thread` 示例：
```json
{
  "id": 1001,
  "sectionId": 1,
  "author": {"id": 8, "username": "alice"},
  "title": "新手拍摄技巧",
  "content": "内容……",
  "status": "published",
  "createdAt": "2025-12-20T12:00:00Z",
  "updatedAt": "2025-12-20T12:00:00Z"
}
```

## 4. 用户 Users（新增）
- GET `/api/v1/users`
  - Query：`{ q?: string, page?: number, size?: number, fields?: string }`
  - 说明：`fields` 控制参与匹配的字段集合，逗号分隔；示例：`fields=username,nickname` 表示仅按用户名与昵称进行匹配。若未传递 `fields`，默认按用户名/邮箱/手机号匹配。
  - Res：`{ items: User[], page, size, total }`，`User = { id, username, email, phone, createdAt }`
- GET `/api/v1/users/{id}/profile`
  - Res：`{ username, nickname, avatarUrl, bio, homepageUrl, location, links: string[] }`

备注：为避免隐私风险，前端的搜索与联想建议会优先携带 `fields=username,nickname`，并在展示层过滤掉仅邮箱/手机号命中的结果。

## 5. 回复 Posts（后续）
- GET `/threads/{id}/posts` → `{ items: Post[], page, size, total }`
- POST `/threads/{id}/posts`（需登录）→ `{ content, parentId? }` → `Post`

## 6. 交互与规则
- 速率限制：登录接口与发帖接口建议限速（如 IP/用户级）。
- CORS：允许前端来源（本地开发与指定域名）。
- 内容过滤：基础敏感词与脚本过滤，避免 XSS。

---
## 用户设置与我的内容（新增）

本节定义“资料设置、通知管理、第三方关联（预留）”以及“我的帖子/评论”的接口约定。统一以 `AccessToken` 鉴权，所有 `me` 路径仅允许当前用户访问。

### 1. 资料设置（Profile）
- `GET /api/v1/users/me`
  - 返回：`{ id, username, nickname, avatarUrl, bio, homepageUrl, location, links: string[] }`
- `PATCH /api/v1/users/me`
  - 请求体：允许部分更新上述字段（任意子集），示例：
  - `{
      nickname?: string,
      avatarUrl?: string,
      bio?: string,
      homepageUrl?: string,
      location?: string,
      links?: string[]
    }`
  - 返回：更新后的完整 Profile；服务端需进行字段白名单校验与长度限制。

### 2. 用户偏好设置（Settings）
- `GET /api/v1/users/me/settings`
  - 返回：`{
      display: { theme: 'light'|'dark'|'system', fontScale: number, highContrast: boolean },
      markdown: { defaultMode: 'edit'|'preview', math: boolean, codeHighlight: boolean },
      comments: { showFloorLabel: boolean, autoCollapseCountThreshold: number, autoCollapseWidthThreshold: number, defaultExpanded: boolean },
      notifications: { inApp: { reply: boolean, mention: boolean, like: boolean, system: boolean }, email: { enabled: boolean, frequency: 'instant'|'daily'|'weekly' } },
      privacy: { allowDMFrom: 'all'|'following'|'none', showOnlineStatus: boolean, blockedUserIds: number[] }
    }`
- `PATCH /api/v1/users/me/settings`
  - 请求体：同上结构，允许部分更新（任意层级字段）。
  - 返回：更新后的完整 Settings；服务端需合并更新并持久化。

### 3. 通知管理（Notifications）
- `GET /api/v1/notifications`
  - 查询参数：`{ type?: 'reply'|'mention'|'like'|'system', unread?: boolean, page?: number, size?: number }`
  - 返回：`{ items: Notification[], page, size, total }`，其中 `Notification = { id, type, title, content, createdAt, read: boolean, relatedThreadId?: number, relatedPostId?: number }`
- `PATCH /api/v1/notifications/:id/read`
  - 标记单条通知为已读；返回：`{ id, read: true }`
- `GET /api/v1/notifications/settings`
  - 返回：与 `settings.notifications` 一致（仅通知相关部分）。
- `PATCH /api/v1/notifications/settings`
  - 请求体/返回：同上，允许部分更新。

### 4. 第三方关联（预留接口）
- `GET /api/v1/users/me/connected-accounts`
  - 返回：`{ items: ConnectedAccount[] }`，`ConnectedAccount = { provider: 'github'|'google'|'weixin'|string, connected: boolean, connectedAt?: string }`
- `POST /api/v1/users/me/connected-accounts/:provider/connect`
  - 预留：用于发起绑定流程（可能重定向至第三方OAuth）；返回：`{ ok: true }` 或任务/跳转信息。
- `DELETE /api/v1/users/me/connected-accounts/:provider`
  - 解绑指定第三方；返回：`{ ok: true }`。

### 5. 我的帖子（Threads — 我发布的）
- `GET /api/v1/users/me/threads`
  - 查询参数：`{ q?: string, sectionId?: number, page?: number, size?: number, sort?: 'createdAt'|'updatedAt' }`
  - 返回：`{ items: Thread[], page, size, total }`，`Thread = { id, title, sectionId, sectionName, createdAt, updatedAt, authorId, authorUsername }`
- `GET /api/v1/threads`
  - 扩展：支持 `authorId` 或 `mine=true` 过滤当前用户，参数：`{ q?, sectionId?, authorId?, mine?, page?, size? }`
- `PATCH /api/v1/threads/:id`
  - 请求体：`{ title?: string, content?: string, sectionId?: number }`
  - 返回：更新后的线程；服务端需自动刷新 `updatedAt`。
- `DELETE /api/v1/threads/:id`
  - 删除本人帖子；返回：`{ ok: true }`。

### 6. 我的评论（Posts — 我发表的）
- `GET /api/v1/users/me/posts`
  - 查询参数：`{ q?: string, threadId?: number, page?: number, size?: number }`
  - 返回：`{ items: Post[], page, size, total }`，`Post = { id, threadId, threadTitle?, content, createdAt, authorId, authorUsername, replyToPostId?: number }`
    - 说明：`threadTitle` 为所属帖子的标题，便于前端“我的评论”列表直观展示；若后端无法获取则可为空。
- `GET /api/v1/posts`
  - 扩展：支持 `authorId` 或 `mine=true` 过滤当前用户，参数：`{ q?, threadId?, authorId?, mine?, page?, size? }`
- `DELETE /api/v1/posts/:id`
  - 删除本人评论；返回：`{ ok: true }`。

### 鉴权与错误
- 所有 `me` 路径需校验 `AccessToken` 并绑定到用户ID；非本人资源操作返回 403。
- 常见错误码：`400` 参数错误、`401` 未登录、`403` 权限不足、`404` 资源不存在、`429` 频率限制、`500` 服务器错误。

### 变更说明
- `listThreads` 现有接口建议兼容 `authorId` 与 `mine` 两个新参数以统一搜索逻辑。
- `updatedAt` 字段在 `PATCH /threads/:id` 成功后由服务端更新时间戳。
 - `Post` 返回结构新增可选字段 `threadTitle`，用于在“我的评论”列表显示所属帖子标题。

### 前端交互补充（锚点跳转约定）
- “我的评论”列表中的帖子链接格式：`/threads/{threadId}#post-{postId}`。
- 帖子详情页接收锚点后应定位并高亮对应评论元素（`id="post-{postId}"`），必要时展开折叠楼层并切换到包含该评论的分页。
- 该约定不影响后端接口，仅为前端内部约束，便于跨页面定位评论。

## 7. 版本控制与弃用策略（新增）
- 版本前缀：统一使用 `Base URL=/api/v1`；未来升级以 `/api/v2` 引入不兼容变更。
- 兼容策略：
  - 小变更（新增字段/参数）保持兼容，并在文档标注“新增”。
  - 弃用字段以返回期内保留并标注 `deprecated: true`（如以附加字段形式），在下一主版本删除。
- 生命周期：
  - `v1` 为当前活动版本；
  - 弃用项至少保留 2 个小版本周期；提前在变更日志与响应头中提示。

## 8. 统一分页响应模型（新增）
- 统一结构：
```
{ "items": [/* T */], "page": 1, "size": 20, "total": 123 }
```
- 约定：
  - `page` 从 1 开始；
  - `size` 最大 100（超出按 100 处理）；
  - `total` 为满足过滤条件的总记录数。
- 错误参数：当 `page<1` 或 `size<1` 返回 400，并给出 `code="INVALID_PAGINATION"`。

## 9. 错误码枚举与格式（新增）
- 格式：
```
{ "message": "错误提示", "code": "ERR_CODE", "details": {"field": "..."} }
```
- 常见错误码：
  - `INVALID_ARGUMENT` 参数非法
  - `UNAUTHORIZED` 未登录
  - `FORBIDDEN` 权限不足
  - `NOT_FOUND` 资源不存在
  - `RATE_LIMITED` 请求过于频繁
  - `CONFLICT` 资源冲突（如重复）
  - `SERVER_ERROR` 服务异常
- 约定：
  - 对于字段校验错误，`details` 中返回 `field -> reason`；
  - 统一返回 `traceId`（可选）用于问题定位。

## 10. 速率限制与幂等（新增）
- 速率限制响应头：
  - `X-RateLimit-Limit: <number>`
  - `X-RateLimit-Remaining: <number>`
  - `X-RateLimit-Reset: <epochSeconds>`
- 幂等：对敏感写操作（如发帖）支持 `Idempotency-Key` 请求头；重复请求在窗口内返回同一结果。

## 11. 变更日志（新增）
- 2025-12-28：新增分页统一模型、错误码枚举、版本策略与幂等约定；不影响现有接口签名。