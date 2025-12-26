-- 兼容 H2（local）环境：将 posts/threads.content_md 从 TEXT(CLOB) 改为 VARCHAR
-- 说明：H2 对 CLOB 使用 LOWER/LIKE 等字符串函数会报错，导致检索接口 500。
--      生产环境（MySQL/Postgres）保持 TEXT 类型，由各自 profile 的迁移脚本定义。

-- 调整主题帖内容列为较大的 VARCHAR，避免影响搜索与渲染
ALTER TABLE threads ALTER COLUMN content_md VARCHAR(10000) NOT NULL;

-- 调整评论内容列为较大的 VARCHAR，保持与后端校验（≤3000）相近的上限
ALTER TABLE posts ALTER COLUMN content_md VARCHAR(3000) NOT NULL;