-- 附件（可选）
CREATE TABLE IF NOT EXISTS attachments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    url VARCHAR(512) NOT NULL,
    mime_type VARCHAR(128) NULL,
    size_bytes INT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_attachments_target (target_type, target_id)
);