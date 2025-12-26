-- 用户资料主表
CREATE TABLE IF NOT EXISTS user_profiles (
  user_id BIGINT NOT NULL PRIMARY KEY,
  nickname VARCHAR(255),
  avatar_url VARCHAR(512),
  bio VARCHAR(1024),
  homepage_url VARCHAR(512),
  location VARCHAR(255)
);

-- 用户资料相关链接（ElementCollection）
CREATE TABLE IF NOT EXISTS user_profile_links (
  user_id BIGINT NOT NULL,
  link VARCHAR(1024),
  CONSTRAINT fk_user_profile_links_user FOREIGN KEY (user_id)
    REFERENCES user_profiles(user_id) ON DELETE CASCADE
);