CREATE TABLE notification_stream_cursor (
    user_id BIGINT NOT NULL PRIMARY KEY,
    next_sequence BIGINT NOT NULL
);
CREATE TABLE notification_stream_event (
    user_id BIGINT NOT NULL,
    sequence_id BIGINT NOT NULL,
    notification_id BIGINT NOT NULL,
    event_kind VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (user_id, sequence_id),
    CONSTRAINT fk_notification_stream_event_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE
);
INSERT INTO notification_stream_cursor(user_id,next_sequence)
    SELECT user_id,MAX(id) FROM notifications GROUP BY user_id;
INSERT INTO notification_stream_event(user_id,sequence_id,notification_id,event_kind,occurred_at)
    SELECT user_id,id,id,'created',created_at FROM notifications;
