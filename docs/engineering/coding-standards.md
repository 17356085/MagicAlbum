# 前后端编码与验证规范

## 通用约定

使用 UTF-8、空格缩进和文件末尾换行，具体编辑器设置见根 `.editorconfig`。Vue、TypeScript、JSON、YAML 默认两空格；Java、XML、Python、PowerShell 默认四空格；Windows 批处理保留 CRLF。已有文件不因小改动而整篇重新格式化。

命名应表达业务职责。公共组件使用 PascalCase，状态逻辑使用 `useXxx`，接口与 DTO 避免以 `any` 隐藏契约。注释解释约束和原因，历史实现由版本管理保存，不在配置文件中保留整段废弃实现。

异常必须有明确结果：向用户展示可操作的失败状态、向调用方返回稳定错误，或记录脱敏的降级信息。空 catch 只适合明确允许失败的缓存、浏览器存储等辅助行为，并需要说明降级策略。

## Vue 与 TypeScript

- 页面负责流程协调，组件负责交互和展示，API 模块负责请求与传输类型，store 只承载需要跨组件共享的状态。
- 监听器、计时器、动画和流式连接必须在停止或卸载时释放。异步请求须处理过期结果，不让旧请求覆盖当前页面状态。
- 网络响应不因类型断言而自动可信。逐步补齐实际使用字段的类型与运行时校验，不以大范围 `as any` 消除错误。
- 用户提供的 HTML/Markdown 必须经过已有安全渲染入口；避免另建直接输出原始 HTML 的路径。
- 表单、焦点、窄屏和减少动效遵循 PRODUCT 中的约定。权限判断以服务端为准。

TypeScript 已开启 `strict`，ESLint 对显式 any、未使用变量和 Vue 模板错误设为强制检查，lint 使用零警告门禁。它们不等同于网络响应的运行时校验；外部响应仍需按实际使用字段收窄和验证，不能只做类型断言。

ESLint 使用 [Vue 插件的 flat config](https://eslint.vuejs.org/user-guide/) 与 [typescript-eslint 的推荐配置](https://typescript-eslint.io/users/configs/)。Vue 单文件组件保留 Vue parser，TypeScript parser 处理 script 内容。项目既有组件允许单词命名，CommonJS 工具允许 require；其他具体规则见 `frontend/eslint.config.mjs`。

```sh
cd frontend
pnpm lint
pnpm type-check
pnpm test
pnpm build
```

## Java 与 Spring

- Controller 负责参数校验、已验证身份与 HTTP 契约；Service 负责权限、业务规则及事务；Repository 负责持久化查询。响应使用 DTO，避免直接暴露 Entity。
- 写操作检查目标归属及访问权限；禁止用可伪造请求头作为用户身份。必须用测试覆盖未登录、非目标用户及管理员边界。
- 事务跨多步写入时，应覆盖失败回滚。外部 HTTP、消息和缓存有独立失败语义，不把数据库事务等同于外部副作用已经完成。
- 数据访问使用参数绑定；分页、长度、枚举和资源大小在入口验证，在服务内保留必要的边界处理。
- 缓存是可降级优化，写操作要更新或失效对应缓存。不要在请求流程中使用 Redis KEYS 枚举全库。
- 配置优先使用明确的构造器注入。密钥来自环境或本地不跟踪文件，不写进代码、日志和异常响应。

```sh
mvn -B test
```

测试使用独立内存数据库，并验证接口、权限和读回结果。根 POM 将 Checkstyle 绑定到 `validate`，覆盖三个服务的生产源码；规则见 `config/checkstyle.xml`：明确普通导入、删除无用/重复导入、文件与顶层类型一致、禁止内部 JDK API 与 finalizer。保留现有排版，避免全库格式化。测试允许静态通配导入；测试源码由编译器和测试运行检查。

`node scripts/Check-JavaArchitecture.mjs` 检查源代码中声明的层间依赖：DTO 不依赖实体/服务/仓库/控制器，实体不依赖上层，仓库不依赖 Web，服务不依赖控制器注解，三个应用不直接导入彼此的 Java 实现。共享领域枚举放在业务域 `model/`；Repository 允许查询 DTO 投影。检查覆盖 imports 和显式全限定应用引用，不分析反射和运行时装配；目前没有接入 SpotBugs，也不把这些规则称为完整语义审计。

分页响应统一为 `items/page/size/total`，对外页码从 1 开始。后端在服务中约束实际每页数量，响应必须返回约束后的 size；标签和通知保留专属额外字段。前端 API 的 `decodePageResponse` 校验分页元数据，畸形响应抛错；旧数组格式的兼容仅在 API 入口，总数只能表达这一页的长度。此解析器不验证每个业务 DTO 的全部字段。

通知可靠投递与超时、内部凭证要求见 [通知投递与运维](notification-delivery.md)。

## 结构检查与交付

`node scripts/Check-Repository.mjs` 检查主模块入口、根目录散落产物、Java 包与目录、前端本地导入以及工程文档相对链接。它是结构与引用检查，不是完整代码规范认证。

修改后运行相关测试；涉及公共模块或目录迁移时再运行全量模块检查。报告中区分“已验证”“待验证”和“建议”，不要用构建成功代替真实外部服务、性能和生产环境验收。
