package com.example.demo.ai.service;

/**
 * Safe, stable categories for failures while generating a summary.
 */
public enum AiSummaryFailureCode {
    TIMEOUT(true),
    TRANSPORT(true),
    RATE_LIMIT(true),
    UPSTREAM(true),
    AUTH(false),
    INVALID_REQUEST(false),
    INVALID_RESPONSE(false),
    EMPTY_RESPONSE(false),
    RESPONSE_TOO_LARGE(false),
    CANCELLED(false);

    private final boolean retryable;

    AiSummaryFailureCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
