ALTER TABLE media_processing_job
    ADD COLUMN lease_token_hash VARCHAR(64) NULL,
    ADD COLUMN lease_until TIMESTAMP(6) NULL,
    ADD COLUMN candidate_key VARCHAR(512) NULL,
    ADD COLUMN candidate_url VARCHAR(2048) NULL,
    ADD COLUMN candidate_sha256 VARCHAR(64) NULL,
    ADD COLUMN candidate_type VARCHAR(32) NULL,
    ADD COLUMN candidate_size BIGINT NULL,
    ADD COLUMN candidate_width INT NULL,
    ADD COLUMN candidate_height INT NULL,
    ADD COLUMN output_key VARCHAR(512) NULL,
    ADD COLUMN output_url VARCHAR(2048) NULL,
    ADD COLUMN output_sha256 VARCHAR(64) NULL,
    ADD COLUMN output_type VARCHAR(32) NULL,
    ADD COLUMN output_size BIGINT NULL,
    ADD COLUMN output_width INT NULL,
    ADD COLUMN output_height INT NULL,
    ADD COLUMN error_code VARCHAR(64) NULL;
CREATE INDEX idx_media_job_expired ON media_processing_job(status, lease_until, job_id);
ALTER TABLE media_asset DROP CHECK chk_media_asset_processing_status;
ALTER TABLE media_asset ADD CONSTRAINT chk_media_asset_processing_status
    CHECK (processing_status IN ('NOT_REQUESTED','PENDING','UNSUPPORTED','PROCESSING','SUCCEEDED','FAILED','CANCELLED'));
