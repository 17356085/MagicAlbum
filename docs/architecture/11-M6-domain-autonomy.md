# M6 领域自治

从已通过 14 项 CI 的候选提交 `d4a8e75` 继续。身份负责账号，社交负责关系，内容负责评论及帖子。两个聊天拓扑沿用同一套社交规则。[M5 运行基线](https://github.com/17356085/MagicAlbum/actions/runs/45388209506)

| 审计项 | 本轮改动 | 验收 |
| --- | --- | --- |
| ARCH-06 | 治理和管理员引导通过身份提供的账号投影工作。帖子查询账号和分区时使用对应领域的接口。身份仍持有账号行锁 | 检查领域依赖规则。覆盖账号引导、角色与范围变更、分区写入及帖子通知的旧行为 |
| ARCH-07 | 评论使用身份查询和匿名评论页缓存接口。缓存适配器负责既有 Redis 键、JSON 和两分钟过期，评论服务负责权威数据校验与提交后失效 | 核对缓存失效和回退、个性化页隔离、评论状态和互动通知 |
| ARCH-09 | Social 持有关注、好友申请和拉黑，账号资料仍从 Identity 读取。解除好友在关系锁内清除申请形成的好友关系，并在互关时只取消发起者的关注 | 默认和分库均走页面和网关。验证双向好友来源、单向关注保留、历史保留、非好友额度及并发授权读取 |

关注移动到社交代码包后沿用 `user_follows` 表和现有索引。首页的关注流仍在同一个数据库内查询内容和关注关系，独立 Feed 读模型按 M9 的查询和分页验收推进。好友状态依据双方已提交的关系，分库发送继续使用两秒授权快照。

[社交状态和并发规则](05-社交关系设计.md) · [领域依赖规则](../../config/java-domain-couplings.json) · [典型浏览器旅程](../../tests/e2e/playwright/test_m0_journey.py)

## 复验命令

```bash
node scripts/Check-Repository.mjs
node scripts/Check-JavaArchitecture.mjs
mvn -B -pl backend,chat-service -am -Dsurefire.failIfNoSpecifiedTests=false '-Dtest=ChatIntegrationTest,RemoteChatDirectoryTest,AdminAccessServiceTest,GovernanceServiceTest,SectionCacheRaceTest,UserFollow*Test,ThreadService*Test,ThreadRepositoryDataJpaTest,PostService*Test' test
bash scripts/ci/compose-browser-smoke.sh
BLUEALBUM_SPLIT_CHAT_CHECK=1 bash scripts/ci/compose-browser-smoke.sh
```

两种拓扑的浏览器报告都记录 `friend_removal_browser=peer_follow_preserved`。完整门禁继续覆盖 M5 的实时恢复、两秒授权、切流回退和备份恢复。[CI 配置](../../.github/workflows/ci.yml)

## 验收结果

提交 `168fa9d91c85ab2ed52593b668faafec40768777` 的 14 项 CI 门禁均通过。两种聊天拓扑的浏览器验收都确认了单向关注保留，完整 Python 回归通过 148 项。Java 领域规则的例外清单由 37 条减至 29 条。[M6 同版本验收](https://github.com/17356085/MagicAlbum/actions/runs/45396326553)
