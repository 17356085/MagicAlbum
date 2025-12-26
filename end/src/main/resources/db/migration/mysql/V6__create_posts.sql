-- 回复/帖子评论
CREATE TABLE IF NOT EXISTS posts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    thread_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content_md MEDIUMTEXT NOT NULL,
    reply_to_post_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_posts_thread_created (thread_id, created_at),
    CONSTRAINT fk_posts_thread FOREIGN KEY (thread_id) REFERENCES threads(id) ON DELETE CASCADE,
    CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_posts_reply_to FOREIGN KEY (reply_to_post_id) REFERENCES posts(id) ON DELETE SET NULL
);