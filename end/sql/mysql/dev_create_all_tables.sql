-- 一次性在本地 MySQL 创建所有核心数据表（与迁移脚本一致）
-- 注意：如已存在则跳过，便于快速联调

USE MagicAlbum;

-- users（已存在于迁移 V2）
CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY users_username_uq (username),
    UNIQUE KEY users_email_uq (email)
);

-- roles & user_roles
CREATE TABLE IF NOT EXISTS roles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY roles_code_uq (code)
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

-- sections
CREATE TABLE IF NOT EXISTS sections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    slug VARCHAR(128) NOT NULL,
    description TEXT NULL,
    rules TEXT NULL,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY sections_slug_uq (slug),
    KEY idx_sections_name (name)
);

-- threads
CREATE TABLE IF NOT EXISTS threads (
    id BIGINT NOT NULL AUTO_INCREMENT,
    section_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    title VARCHAR(256) NOT NULL,
    content_md MEDIUMTEXT NOT NULL,
    spoiler BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_threads_section_created (section_id, created_at),
    CONSTRAINT fk_threads_section FOREIGN KEY (section_id) REFERENCES sections(id) ON DELETE RESTRICT,
    CONSTRAINT fk_threads_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE RESTRICT
);

-- posts
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

-- tags & thread_tags
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

-- reports
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

-- thread_stats（可选）
CREATE TABLE IF NOT EXISTS thread_stats (
    thread_id BIGINT NOT NULL,
    replies_count INT NOT NULL DEFAULT 0,
    likes_count INT NOT NULL DEFAULT 0,
    heat_score INT NOT NULL DEFAULT 0,
    PRIMARY KEY (thread_id),
    CONSTRAINT fk_thread_stats_thread FOREIGN KEY (thread_id) REFERENCES threads(id) ON DELETE CASCADE
);

-- attachments（可选）
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