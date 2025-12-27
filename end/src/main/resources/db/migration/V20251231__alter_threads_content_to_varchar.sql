-- 兼容 H2（local）环境：将 threads.content_md 从 TEXT(CLOB) 改为 VARCHAR
-- 说明：在 H2 上对 CLOB 使用 LOWER/LIKE 会抛出错误，导致 /api/v1/threads 查询 500。
--      生产环境（MySQL/Postgres）使用各自 profile 的迁移脚本定义合适类型。

ALTER TABLE threads ALTER COLUMN content_md VARCHAR(16384);