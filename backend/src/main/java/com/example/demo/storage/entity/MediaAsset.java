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
        name = "media_asset",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_media_asset_provider_object_key",
                columnNames = {"provider", "object_key"}
        ),
        indexes = {
                @Index(name = "idx_media_asset_owner_status", columnList = "owner_id, status, asset_id"),
                @Index(name = "idx_media_asset_status_updated", columnList = "status, updated_at, asset_id")
        }
)
public class MediaAsset {

    public static final String STATUS_UPLOADING = "UPLOADING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_UPLOAD_FAILED = "UPLOAD_FAILED";

    public static final String PROCESSING_NOT_REQUESTED = "NOT_REQUESTED";
    public static final String PROCESSING_PENDING = "PENDING";
    public static final String PROCESSING_UNSUPPORTED = "UNSUPPORTED";

    @Id
    @Column(name = "asset_id", nullable = false, length = 36, updatable = false)
    private String assetId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(nullable = false, length = 16, updatable = false)
    private String provider;

    @Column(name = "object_key", nullable = false, length = 512, updatable = false)
    private String objectKey;

    @Column(nullable = false, length = 2048, updatable = false)
    private String url;

    @Column(name = "content_type", nullable = false, length = 32, updatable = false)
    private String contentType;

    @Column(name = "byte_size", nullable = false, updatable = false)
    private long byteSize;

    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "processing_status", nullable = false, length = 20)
    private String processingStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
            status = STATUS_UPLOADING;
        }
        if (processingStatus == null) {
            processingStatus = PROCESSING_NOT_REQUESTED;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
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
