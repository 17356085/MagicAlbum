-- 主题统计（可选）
CREATE TABLE IF NOT EXISTS thread_stats (
    thread_id BIGINT NOT NULL,
    replies_count INT NOT NULL DEFAULT 0,
    likes_count INT NOT NULL DEFAULT 0,
    heat_score INT NOT NULL DEFAULT 0,
    PRIMARY KEY (thread_id),
    CONSTRAINT fk_thread_stats_thread FOREIGN KEY (thread_id) REFERENCES threads(id) ON DELETE CASCADE
);