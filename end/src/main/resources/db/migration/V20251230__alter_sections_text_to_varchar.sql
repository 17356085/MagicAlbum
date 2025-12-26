-- 兼容 H2（local）环境：将 sections.description / sections.rules 从 TEXT(CLOB) 改为 VARCHAR
-- 说明：在 H2 上对 CLOB 使用 LOWER/LIKE 会抛出错误，导致 /api/v1/sections 查询 500。
--      生产环境（MySQL/Postgres）保持 TEXT 类型由各自 profile 的迁移脚本定义。

ALTER TABLE sections ALTER COLUMN description VARCHAR(2048);
ALTER TABLE sections ALTER COLUMN rules VARCHAR(2048);