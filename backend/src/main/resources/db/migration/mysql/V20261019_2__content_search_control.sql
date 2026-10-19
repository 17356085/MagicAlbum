CREATE TABLE content_search_control (
    id BIGINT NOT NULL,
    pause_owner VARCHAR(36) NULL,
    pause_until TIMESTAMP(6) NULL,
    active_index VARCHAR(128) NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

INSERT INTO content_search_control (id, ready)
VALUES (1, FALSE);
