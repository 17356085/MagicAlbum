package com.example.demo.storage.dto;

/**
 * Public media state. Internal ownership, provider and digest fields are deliberately omitted.
 */
public record MediaAssetStatus(
        String assetId,
        String url,
        String status,
        String jobId,
        String processingStatus,
        String previewUrl,
        String errorCode
) {
    public MediaAssetStatus(String assetId, String url, String status, String jobId, String processingStatus) {
        this(assetId, url, status, jobId, processingStatus, null, null);
    }
}
