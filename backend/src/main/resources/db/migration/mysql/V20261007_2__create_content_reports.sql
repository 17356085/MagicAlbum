-- Persistent user reports and administrator moderation decisions.
CREATE TABLE IF NOT EXISTS content_reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reporter_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    detail VARCHAR(500) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reviewer_id BIGINT NULL,
    resolved_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_reports_reporter_target (reporter_id, target_type, target_id),
    KEY idx_content_reports_status_created (status, created_at, id),
    KEY idx_content_reports_target (target_type, target_id),
    CONSTRAINT fk_content_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_content_reports_reviewer FOREIGN KEY (reviewer_id) REFERENCES users(id) ON DELETE RESTRICT
);
