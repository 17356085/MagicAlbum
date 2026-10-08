-- 添加 AI 摘要相关字段
ALTER TABLE threads ADD COLUMN summary TEXT;
ALTER TABLE threads ADD COLUMN summary_status VARCHAR(32) DEFAULT 'PENDING';

-- 历史数据初始化
UPDATE threads SET summary_status = 'PENDING' WHERE summary IS NULL;
