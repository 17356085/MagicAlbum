ALTER TABLE notifications ADD COLUMN idempotency_key CHAR(36) NULL;
CREATE UNIQUE INDEX uk_notifications_idempotency_key ON notifications (idempotency_key);
