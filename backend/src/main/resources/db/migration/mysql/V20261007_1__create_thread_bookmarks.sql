CREATE TABLE IF NOT EXISTS thread_bookmarks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    thread_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_thread_bookmarks_user_thread UNIQUE (user_id, thread_id),
    KEY idx_thread_bookmarks_user_created (user_id, created_at, thread_id),
    KEY idx_thread_bookmarks_thread (thread_id),
    CONSTRAINT fk_thread_bookmarks_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_thread_bookmarks_thread FOREIGN KEY (thread_id) REFERENCES threads(id) ON DELETE CASCADE
);
