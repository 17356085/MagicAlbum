-- 角色与用户-角色关联
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