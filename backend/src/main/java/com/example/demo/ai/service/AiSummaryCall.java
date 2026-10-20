package com.example.demo.ai.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A cancellable asynchronous summary request.
 */
public final class AiSummaryCall {
    private final CompletableFuture<AiSummaryResult> completion;
    private final Runnable cancelAction;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public AiSummaryCall(
            CompletableFuture<AiSummaryResult> completion,
            Runnable cancelAction
    ) {
        this.completion = Objects.requireNonNull(completion, "completion");
        this.cancelAction = cancelAction == null ? () -> { } : cancelAction;
    }

    public CompletableFuture<AiSummaryResult> completion() {
        return completion;
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            cancelAction.run();
        }
    }

    static AiSummaryCall failed(AiSummaryFailureCode code) {
        CompletableFuture<AiSummaryResult> completion = new CompletableFuture<>();
        completion.completeExceptionally(new AiSummaryException(code));
        return new AiSummaryCall(completion, () -> { });
    }
}
