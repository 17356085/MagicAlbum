-- 标签与主题-标签关联
CREATE TABLE IF NOT EXISTS tags (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY tags_name_type_uq (name, type)
);

CREATE TABLE IF NOT EXISTS thread_tags (
    thread_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (thread_id, tag_id),
    CONSTRAINT fk_thread_tags_thread FOREIGN KEY (thread_id) REFERENCES threads(id) ON DELETE CASCADE,
    CONSTRAINT fk_thread_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);