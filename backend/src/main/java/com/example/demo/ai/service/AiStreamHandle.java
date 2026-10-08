package com.example.demo.ai.service;

import java.util.concurrent.CompletableFuture;

/**
 * A cancellable provider stream. The HTTP request is asynchronous and the
 * caller can cancel both the request and its downstream subscription.
 */
public final class AiStreamHandle {
    private final CompletableFuture<Void> completion;
    private final Runnable cancelAction;

    public AiStreamHandle(CompletableFuture<Void> completion, Runnable cancelAction) {
        this.completion = completion;
        this.cancelAction = cancelAction;
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public void cancel() {
        cancelAction.run();
    }
}
