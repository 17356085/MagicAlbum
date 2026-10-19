# M4 实施前调查与边界

2026-10-20，只读源码调查。M3 的最终运行结果仍按[阶段记录](stage-progress.md)核对；本文不表示 M4 已实现或验收通过。范围来自[既定路线图](development-roadmap.md)，不增加可选 RAG、额外服务拆分或公网部署。

## M4A：社区管理

当前管理员由 `AdminAccessService` 在启动时用配置邮箱解析并缓存一个用户 ID；JWT 只有身份，尚无持久化角色、版主分区授权、发言限制或撤销机制。举报支持忽略/隐藏，事务中锁定举报再调用内容服务；线程隐藏递增内容版本并生成事件，评论隐藏只变更状态和清缓存。目前没有恢复接口或完整操作历史。

实现依据：[管理员解析](../../backend/src/main/java/com/example/demo/admin/security/AdminAccessService.java)、[举报事务](../../backend/src/main/java/com/example/demo/moderation/service/ContentReportService.java)、[线程状态与事件](../../backend/src/main/java/com/example/demo/threads/service/ThreadService.java)、[评论操作](../../backend/src/main/java/com/example/demo/posts/service/PostService.java)、[分区服务](../../backend/src/main/java/com/example/demo/sections/service/SectionService.java)。分区当前只有读取接口，`visible` 表示导航展示，不能直接当作内容保密或归档权限。

主代理确定以下实施边界，迁移及具体接口在实施批次进一步冻结：

- 角色与分区授权持久化。普通用户、分区版主和全局管理员使用同一身份认证，管理操作在服务端核对当前权限，不依靠前端入口或 JWT 中的旧角色。角色撤销提交后，后续请求应立即按新权限判断；保留至少一个可用管理员。
- 配置邮箱只作为一次性的初始管理员引导，不在每次重启时重新授予已经撤销的角色。角色及范围变更必须有审计记录。
- 评论的管理权限继承其父线程当前所属分区。版主只处理授权分区，管理员可以跨区；线程移动分区后，旧范围不能继续授予操作权。
- 恢复只适用于审核隐藏的内容，不恢复作者删除的内容。恢复评论要求父线程正常；隐藏/恢复均填写原因，并将操作者、动作、目标、时间和请求关联 ID 与状态变更一同持久化。
- 分区归档保留历史阅读与链接，停止普通用户在该分区新增或编辑帖子/评论及移入新内容；作者删除自己的内容继续遵守既有归属规则。归档与导航展示分开定义。
- 发言限制影响新增及编辑线程/评论，登录、退出和浏览仍可用。范围、期限和理由由服务端执行，撤销后按数据库状态生效。
- 隐藏与恢复要核对详情、列表、搜索、摘要读取、通知跳转及回复计数/缓存。当前搜索事件以线程为聚合，不为尚未索引的评论盲目增加一套消息协议。

阶段验收至少覆盖普通用户、同区/跨区版主、管理员、角色撤销、多举报并发、恢复幂等、迁移、分区归档和限时发言限制，并从数据库及各读取入口核对操作结果。现有测试只覆盖固定管理员和举报隐藏，不替代这些新场景。

## M4B：真实摘要

当前前端重新生成固定传 `force=true`，没有任务 ID 或取消请求；后端在登录和限流后把线程置为 PENDING，消息只携带线程 ID。消费者临时生成 owner，180 秒租约，最多五次领取；完成保护依赖 owner、租约及 `updatedAt`，尚未显式绑定 `contentVersion`。真实 provider 有 HTTP transport，但失败类别合并，未持久化模型用量和关联证据；Compose 仍配置 Mock。

实现依据：[摘要接口](../../backend/src/main/java/com/example/demo/ai/controller/AiController.java)、[摘要消费者](../../backend/src/main/java/com/example/demo/threads/service/ThreadSummaryListener.java)、[结果条件更新](../../backend/src/main/java/com/example/demo/threads/repo/ThreadRepository.java)、[真实 provider](../../backend/src/main/java/com/example/demo/ai/service/RealAiProvider.java)、[页面调用](../../frontend/src/pages/thread-detail/ThreadDetailSummary.vue)。现有本机 HTTP 模拟服务和 H2/Mockito 测试不能证明外部模型已执行。

实施边界：

- 为摘要建立可查询的任务 ID、内容版本、状态、触发者、尝试和固定失败类别；接受结果必须匹配当前任务及内容版本，旧回调不得覆盖新内容。
- 已登录用户可以读取正常公开帖子的摘要；触发、取消和重试由作者或具有当前分区管理权限者执行。强制重试创建新任务，不隐式接管仍在处理的任务。
- 取消后停止接受结果并尽力中止传输；已发出的外部请求可能已经执行和计费，不能将本地取消当作供应商退款或硬中断保证。
- 设置任务级并发、请求/输出上限和用户预算，定义重试计费规则；记录实际 provider、模型、关联 ID、必要用量及耗时，不记录正文或密钥。
- 真实验收使用私有配置，完成正常/长文本、故障/超时、旧版本晚回、重复请求、取消及额度耗尽场景，再回读数据库中的摘要、状态和版本。模型协议模拟与真实外部调用结果分开记录。

RAG 仍为可选后续范围；不能以“完成全部阶段”自动扩大到混合检索、额外向量数据库或多步 Agent。

## 全阶段收口

M0/M1 未收口项继续保留，真机、Safari、系统软键盘或外部凭据缺失的必要验收不勾选完成。全部既定阶段完成后，再对前后端、Go、迁移、配置及部署脚本统一审查，修复后执行完整单元、集成、端到端、权限和恢复回归，保存证据并核实最终代码已推送。
