-- Operational lock rows owned by the chat database. Rows are created lazily
-- by the remote directory when an account's deterministic bucket is used.
CREATE TABLE chat_write_locks (
    bucket_id SMALLINT NOT NULL PRIMARY KEY,
    CONSTRAINT ck_chat_write_lock_bucket CHECK (bucket_id BETWEEN 0 AND 255)
);
