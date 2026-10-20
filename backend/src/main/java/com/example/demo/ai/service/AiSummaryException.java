package com.example.demo.ai.service;

/**
 * A summary failure whose message is intentionally safe for callers and logs.
 */
public final class AiSummaryException extends IllegalStateException {
    private static final String SAFE_MESSAGE = "AI summary generation failed";

    private final AiSummaryFailureCode code;

    public AiSummaryException(AiSummaryFailureCode code) {
        super(SAFE_MESSAGE);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public AiSummaryFailureCode code() {
        return code;
    }

    public boolean retryable() {
        return code.retryable();
    }
}
