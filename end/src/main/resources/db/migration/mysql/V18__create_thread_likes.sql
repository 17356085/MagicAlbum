CREATE TABLE IF NOT EXISTS thread_likes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    thread_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_thread_likes_thread_user UNIQUE (thread_id, user_id),
    CONSTRAINT fk_thread_likes_thread FOREIGN KEY (thread_id) REFERENCES threads(id) ON DELETE CASCADE,
    CONSTRAINT fk_thread_likes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_thread_likes_thread ON thread_likes (thread_id);
CREATE INDEX idx_thread_likes_user ON thread_likes (user_id);
