CREATE TABLE media_asset (
    asset_id VARCHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) NOT NULL,
    processing_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (asset_id),
    CONSTRAINT uk_media_asset_provider_object_key UNIQUE (provider, object_key),
    KEY idx_media_asset_owner_status (owner_id, status, asset_id),
    KEY idx_media_asset_status_updated (status, updated_at, asset_id),
    CONSTRAINT chk_media_asset_provider CHECK (provider IN ('local', 's3')),
    CONSTRAINT chk_media_asset_byte_size CHECK (byte_size BETWEEN 1 AND 10485760),
    CONSTRAINT chk_media_asset_content_type CHECK (
        content_type IN ('image/jpeg', 'image/png', 'image/gif', 'image/webp')
    ),
    CONSTRAINT chk_media_asset_sha256 CHECK (sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_media_asset_status CHECK (status IN ('UPLOADING', 'READY', 'UPLOAD_FAILED')),
    CONSTRAINT chk_media_asset_processing_status CHECK (
        processing_status IN ('NOT_REQUESTED', 'PENDING', 'UNSUPPORTED')
    )
) ENGINE=InnoDB;

CREATE TABLE media_processing_job (
    job_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    job_version INT NOT NULL,
    preset VARCHAR(32) NOT NULL,
    processor_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (job_id),
    CONSTRAINT uk_media_processing_job_asset_version_preset
        UNIQUE (asset_id, job_version, preset),
    KEY idx_media_processing_job_status_created (status, created_at, job_id),
    CONSTRAINT chk_media_processing_job_version CHECK (job_version >= 1),
    CONSTRAINT chk_media_processing_job_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_media_processing_job_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT fk_media_processing_job_asset
        FOREIGN KEY (asset_id) REFERENCES media_asset(asset_id)
) ENGINE=InnoDB;

CREATE TABLE media_request_outbox (
    event_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    job_version INT NOT NULL,
    preset VARCHAR(32) NOT NULL,
    processor_version VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_media_request_outbox_job_version UNIQUE (job_id, job_version),
    KEY idx_media_request_outbox_status_occurred (status, occurred_at, event_id),
    CONSTRAINT chk_media_request_outbox_version CHECK (job_version >= 1),
    CONSTRAINT chk_media_request_outbox_status CHECK (
        status IN ('PENDING', 'PUBLISHED', 'FAILED')
    ),
    CONSTRAINT fk_media_request_outbox_asset
        FOREIGN KEY (asset_id) REFERENCES media_asset(asset_id),
    CONSTRAINT fk_media_request_outbox_job
        FOREIGN KEY (job_id) REFERENCES media_processing_job(job_id)
) ENGINE=InnoDB;
