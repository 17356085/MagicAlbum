package com.example.demo.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "media_request_outbox",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_media_request_outbox_job_version",
                columnNames = {"job_id", "job_version"}
        ),
        indexes = @Index(
                name = "idx_media_request_outbox_status_occurred",
                columnList = "status, occurred_at, event_id"
        )
)
@lombok.Getter
@lombok.Setter
public class MediaRequestOutbox {

    public static final String STATUS_PENDING = "PENDING";

    @Id
    @Column(name = "event_id", nullable = false, length = 36, updatable = false)
    private String eventId;

    @Column(name = "asset_id", nullable = false, length = 36, updatable = false)
    private String assetId;

    @Column(name = "job_id", nullable = false, length = 36, updatable = false)
    private String jobId;

    @Column(name = "job_version", nullable = false, updatable = false)
    private int jobVersion;

    @Column(nullable = false, length = 32, updatable = false)
    private String preset;

    @Column(name = "processor_version", nullable = false, length = 64, updatable = false)
    private String processorVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error", length = 64)
    private String lastError;

    @PrePersist
    void onCreate() {
        if (availableAt == null) availableAt = Instant.now();
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        if (status == null) {
            status = STATUS_PENDING;
        }
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
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

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
