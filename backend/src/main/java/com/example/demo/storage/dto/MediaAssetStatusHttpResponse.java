package com.example.demo.storage.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** Public owner status plus the server's media processing capability flag. */
public record MediaAssetStatusHttpResponse(
        @JsonUnwrapped MediaAssetStatus status,
        boolean processingEnabled
) {
}
