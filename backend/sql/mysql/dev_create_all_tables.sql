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
    UNIQUE KEY users_email_uq (email),
    UNIQUE KEY users_phone_uq (phone)
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

-- thread_likes：帖子点赞
CREATE TABLE IF NOT EXISTS thread_likes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    thread_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_thread_likes_thread_user (thread_id, user_id),
    KEY idx_thread_likes_thread (thread_id),
    KEY idx_thread_likes_user (user_id),
    CONSTRAINT fk_thread_likes_thread FOREIGN KEY (thread_id) REFERENCES threads(id) ON DELETE CASCADE,
    CONSTRAINT fk_thread_likes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- post_likes：评论点赞
CREATE TABLE IF NOT EXISTS post_likes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_likes_post_user (post_id, user_id),
    KEY idx_post_likes_post (post_id),
    KEY idx_post_likes_user (user_id),
    CONSTRAINT fk_post_likes_post FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
    CONSTRAINT fk_post_likes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- user_follows：用户关注关系
CREATE TABLE IF NOT EXISTS user_follows (
    id BIGINT NOT NULL AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_follows_follower_following (follower_id, following_id),
    KEY idx_user_follows_follower (follower_id),
    KEY idx_user_follows_following (following_id),
    CONSTRAINT fk_user_follows_follower FOREIGN KEY (follower_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_follows_following FOREIGN KEY (following_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_user_follows_not_self CHECK (follower_id <> following_id)
);

-- user_profiles：用户资料（头像、昵称、个性信息）
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id BIGINT NOT NULL,
    nickname VARCHAR(255) NULL,
    avatar_url VARCHAR(512) NULL,
    bio VARCHAR(1024) NULL,
    homepage_url VARCHAR(512) NULL,
    location VARCHAR(255) NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- user_profile_links：用户资料扩展链接（ElementCollection）
CREATE TABLE IF NOT EXISTS user_profile_links (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    link VARCHAR(1024) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_profile_links_user (user_id),
    CONSTRAINT fk_user_profile_links_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
