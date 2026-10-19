package com.example.demo.storage.dto;

/**
 * Provider-owned metadata captured before the object write starts.
 *
 * @param provider storage provider name (local or s3)
 * @param objectKey immutable provider object key
 * @param url stable URL returned by the selected provider
 * @param contentType validated image media type
 * @param byteSize validated object size
 * @param sha256 lowercase hexadecimal SHA-256 of the object bytes
 */
public record MediaUploadIntent(
        String provider,
        String objectKey,
        String url,
        String contentType,
        long byteSize,
        String sha256
) {
}
