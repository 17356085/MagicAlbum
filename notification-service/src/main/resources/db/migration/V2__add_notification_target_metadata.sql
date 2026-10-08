ALTER TABLE notifications ADD COLUMN thread_id BIGINT NULL;
ALTER TABLE notifications ADD COLUMN target_id BIGINT NULL;
ALTER TABLE notifications ADD COLUMN target_type VARCHAR(10) NULL;
