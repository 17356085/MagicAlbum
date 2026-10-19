package com.example.demo.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "media_processing_job",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_media_processing_job_asset_version_preset",
                columnNames = {"asset_id", "job_version", "preset"}
        ),
        indexes = @Index(
                name = "idx_media_processing_job_status_created",
                columnList = "status, created_at, job_id"
        )
)
@lombok.Getter
@lombok.Setter
public class MediaProcessingJob {

    public static final String STATUS_PENDING = "PENDING";

    @Id
    @Column(name = "job_id", nullable = false, length = 36, updatable = false)
    private String jobId;

    @Column(name = "asset_id", nullable = false, length = 36, updatable = false)
    private String assetId;

    @Column(name = "job_version", nullable = false, updatable = false)
    private int jobVersion;

    @Column(nullable = false, length = 32, updatable = false)
    private String preset;

    @Column(name = "processor_version", nullable = false, length = 64, updatable = false)
    private String processorVersion;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "lease_token_hash", length = 64)
    private String leaseTokenHash;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "candidate_key", length = 512)
    private String candidateKey;

    @Column(name = "candidate_url", length = 2048)
    private String candidateUrl;

    @Column(name = "candidate_sha256", length = 64)
    private String candidateSha256;

    @Column(name = "candidate_type", length = 32)
    private String candidateType;

    @Column(name = "candidate_size")
    private Long candidateSize;

    @Column(name = "candidate_width")
    private Integer candidateWidth;

    @Column(name = "candidate_height")
    private Integer candidateHeight;

    @Column(name = "output_key", length = 512)
    private String outputKey;

    @Column(name = "output_url", length = 2048)
    private String outputUrl;

    @Column(name = "output_sha256", length = 64)
    private String outputSha256;

    @Column(name = "output_type", length = 32)
    private String outputType;

    @Column(name = "output_size")
    private Long outputSize;

    @Column(name = "output_width")
    private Integer outputWidth;

    @Column(name = "output_height")
    private Integer outputHeight;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = STATUS_PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public int getJobVersion() {
        return jobVersion;
    }

    public void setJobVersion(int jobVersion) {
        this.jobVersion = jobVersion;
    }

    public String getPreset() {
        return preset;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    public String getProcessorVersion() {
        return processorVersion;
    }

    public void setProcessorVersion(String processorVersion) {
        this.processorVersion = processorVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
