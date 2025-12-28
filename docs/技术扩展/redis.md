# 技术扩展：Redis 缓存与会话/限流

## 目标
- 为热门接口（如帖子列表）提供缓存加速与限流保护。
- 为会话、临时数据与分布式锁提供基础设施。

## 部署与连接
- 单节点或哨兵/集群模式，推荐生产使用高可用方案。
- Spring Boot 配置示例：
```
spring.data.redis.host=redis
spring.data.redis.port=6379
spring.data.redis.password= # 可选
spring.data.redis.ssl=false
spring.data.redis.timeout=2000
spring.data.redis.lettuce.pool.max-active=16
spring.data.redis.lettuce.pool.max-idle=8
spring.data.redis.lettuce.pool.min-idle=2
```

## 用途与键设计
- 缓存示例：`threads:list:{section}:{page}` → TTL 30~120s；
- 失效策略：帖子创建/更新时，按 section/page 精确删除或标注过期；
- 限流示例：`rate:{ip}:{endpoint}`，`INCR` + TTL；
- 分布式锁示例：`lock:threads:update:{id}` → 设置超时避免死锁。

## 持久化与内存策略
- AOF（追踪写操作）与 RDB（快照）按业务选择；
- `maxmemory-policy` 推荐 `volatile-ttl` 或 `allkeys-lru`；
- 监控关键指标：命中率、内存占用、慢查询。

## 与项目集成建议
- 在 `end` 服务中引入 Spring Data Redis，封装缓存服务层，统一 TTL 与失效策略；
- 针对接口 `/api/v1/threads` 做读缓存，写操作触发精确失效；
- 对登录与敏感操作使用限流与黑名单策略。

## 风险与回滚
- 缓存穿透与雪崩：使用空值缓存、随机 TTL、降级开关；
- 连接与网络抖动：设置超时与重试策略，熔断降级。