package com.example.demo.ai.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Explicit local/test provider. It is never selected by a non-empty real key.
 */
public class MockAiProvider implements AiProvider {
    private static final String PROVIDER_NAME = "mock";
    private static final String MODEL_NAME = "mock-summary";
    private static final int MAX_INPUT_CHARS = 12_000;
    private static final int MAX_OUTPUT_TOKENS = 2_048;

    @Override
    public String generateSummary(String content) {
        return "【测试模式】这是一段自动生成的模拟摘要，用于验证本地流程。";
    }

    @Override
    public AiSummaryCall requestSummary(String content, int maxOutputTokens) {
        if (content == null || content.isBlank() || content.length() > MAX_INPUT_CHARS
                || maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS) {
            return AiSummaryCall.failed(AiSummaryFailureCode.INVALID_REQUEST);
        }
        long started = System.nanoTime();
        AiSummaryResult result = new AiSummaryResult(
                generateSummary(content),
                PROVIDER_NAME,
                MODEL_NAME,
                MODEL_NAME,
                null,
                null,
                null,
                null,
                (System.nanoTime() - started) / 1_000_000L
        );
        return new AiSummaryCall(CompletableFuture.completedFuture(result), () -> { });
    }

    @Override
    public String summaryProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public String summaryModelName() {
        return MODEL_NAME;
    }

    @Override
    public AiStreamHandle chatStream(
            List<Map<String, String>> messages,
            int maxOutputTokens,
            Consumer<String> onContent,
            Consumer<Throwable> onError,
            Runnable onComplete
    ) {
        String lastUserMsg = "未知";
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, String> message = messages.get(i);
                if (message != null && "user".equals(message.get("role"))) {
                    lastUserMsg = message.getOrDefault("content", "未知");
                    break;
                }
            }
        }
        String reply = "【Mock回复】我收到了你说：" + lastUserMsg + "。当前为显式测试模式。";
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            onContent.accept(reply);
            onComplete.run();
            completion.complete(null);
        } catch (Throwable ex) {
            onError.accept(new RuntimeException("AI 测试提供者失败", ex));
            completion.completeExceptionally(ex);
        }
        return new AiStreamHandle(completion, () -> completion.cancel(false));
    }
}
