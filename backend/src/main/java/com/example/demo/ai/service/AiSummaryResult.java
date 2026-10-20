package com.example.demo.ai.service;

/**
 * A completed summary and the provider evidence returned with it.
 */
public record AiSummaryResult(
        String summary,
        String provider,
        String model,
        String responseModel,
        String providerRequestId,
        String providerResponseId,
        Long inputTokens,
        Long outputTokens,
        long durationMillis
) {
}
