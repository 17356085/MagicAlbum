CREATE INDEX idx_media_job_pending_updated ON media_processing_job(status, updated_at, job_id);
