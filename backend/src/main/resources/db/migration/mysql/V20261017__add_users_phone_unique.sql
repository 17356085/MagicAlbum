-- 为历史 users 表补齐与 User.phone 实体声明一致的唯一约束。
--
-- 迁移前可执行只读预检（只返回重复手机号分组数量，不返回手机号）：
-- SELECT COUNT(*) AS duplicate_phone_groups
-- FROM (SELECT phone FROM users GROUP BY phone HAVING COUNT(*) > 1) duplicates;
--
-- 不在迁移中删除、合并或改写账号。若历史库存在重复手机号，下面的
-- ALTER TABLE 会失败，Flyway 会将迁移标记为失败并阻止应用继续启动。
ALTER TABLE users
    ADD CONSTRAINT users_phone_uq UNIQUE KEY (phone);
