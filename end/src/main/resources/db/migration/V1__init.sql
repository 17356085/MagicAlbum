-- Flyway 初始迁移占位文件
-- 在后续里程碑中填充实际表结构（users/roles/sections/threads/posts/tags/reports 等）
-- 目前留空以验证 Flyway 管道可用性

-- 示例：
-- CREATE TABLE IF NOT EXISTS schema_version_placeholder (
--     id BIGSERIAL PRIMARY KEY,
--     created_at TIMESTAMPTZ DEFAULT NOW()
-- );
-- 已移除示例性的 ALTER 语句，避免在空库（H2/local）导致迁移失败