ALTER TABLE media_request_outbox
    ADD COLUMN trace_parent VARCHAR(55) NULL,
    ADD COLUMN trace_state VARCHAR(512) NULL;
