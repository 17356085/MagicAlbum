CREATE TABLE notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    read_flag BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at);
CREATE INDEX idx_notifications_user_read ON notifications (user_id, read_flag);
