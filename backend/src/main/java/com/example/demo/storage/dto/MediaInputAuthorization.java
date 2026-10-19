package com.example.demo.storage.dto;

/**
 * Internal input capability returned only after the worker lease has been
 * checked. It contains the exact provider object key and immutable metadata.
 */
public record MediaInputAuthorization(
        String provider,
        String objectKey,
        String sha256,
        String contentType,
        long byteSize
) {
}
