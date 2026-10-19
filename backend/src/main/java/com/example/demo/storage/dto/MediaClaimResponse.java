package com.example.demo.storage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Response returned when a media processing job is claimed by the worker. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaClaimResponse(
        String status,
        String assetId,
        Integer jobVersion,
        String preset,
        String processorVersion,
        String leaseToken,
        Instant leaseUntil,
        String inputSha256,
        String contentType,
        Long byteSize
) {
}
