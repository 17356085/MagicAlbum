-- 为 posts 表补充缺失的状态字段，匹配实体与查询（Post.status = 'NORMAL'）
-- 说明：为兼容较低版本的 MySQL（不支持 IF NOT EXISTS），直接添加列。
-- 若列已存在，该语句会因重复列报错；在正常迁移路径中（此前建表不含 status），应能成功执行。
ALTER TABLE posts
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'NORMAL';

-- 可选：将 updated_at 设为非空并默认当前时间（若已有为 NULL 的历史记录，可按需清洗）
-- 注意：MySQL 8 支持多列的默认与 ON UPDATE；此处不强制修改以避免影响已有数据
-- ALTER TABLE posts MODIFY COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;