CREATE TABLE IF NOT EXISTS user_notification_settings (
    user_id BIGINT NOT NULL,
    reply_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    mention_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    like_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    follow_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_notification_settings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
