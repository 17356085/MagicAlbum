-- 举报记录
CREATE TABLE IF NOT EXISTS reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    reporter_id BIGINT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_reports_status_created (status, created_at),
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE RESTRICT
);