package com.example.demo.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * OpenAI-compatible provider with a real asynchronous SSE transport.
 */
public class RealAiProvider implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(RealAiProvider.class);

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final URI completionUri;
    private final String apiKey;
    private final AiPromptBuilder promptBuilder;
    private final AiResponseParser responseParser;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public RealAiProvider(String baseUrl, String apiKey, String modelName, Duration timeout) {
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.promptBuilder = new AiPromptBuilder(modelName);
        this.responseParser = new AiResponseParser();
        this.objectMapper = new ObjectMapper();
        this.completionUri = URI.create(normalizeBaseUrl(baseUrl) + "/chat/completions");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .defaultHeader("Authorization", "Bearer " + this.apiKey)
                .build();
    }

    @Override
    public String generateSummary(String content) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", promptBuilder.getModelName(),
                    "messages", promptBuilder.buildLegacySummaryMessages(content),
                    "temperature", 0.3,
                    "max_tokens", 512
            );
            Map<?, ?> response = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
            String parsed = responseParser.parseResponse(response);
            return parsed == null || parsed.isBlank() ? "无法生成摘要，请稍后重试" : parsed;
        } catch (Exception ex) {
            log.warn("ai_summary_failed model={} error={}",
                    promptBuilder.getModelName(), ex.getClass().getSimpleName());
            return "摘要生成失败，请稍后重试";
        }
    }

    @Override
    public AiStreamHandle chatStream(
            List<Map<String, String>> messages,
            int maxOutputTokens,
            Consumer<String> onContent,
            Consumer<Throwable> onError,
            Runnable onComplete
    ) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        final CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> requestFuture;

        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "model", promptBuilder.getModelName(),
                    "messages", promptBuilder.buildLegacyChatMessages(messages),
                    "temperature", 0.7,
                    "max_tokens", maxOutputTokens,
                    "stream", true
            ));
            HttpRequest request = HttpRequest.newBuilder(completionUri)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();
            requestFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher());
        } catch (Exception ex) {
            Throwable safe = providerFailure(ex);
            onError.accept(safe);
            completion.completeExceptionally(safe);
            return new AiStreamHandle(completion, () -> completion.cancel(false));
        }

        requestFuture.whenComplete((response, error) -> {
            if (error != null) {
                Throwable safe = providerFailure(error);
                onError.accept(safe);
                completion.completeExceptionally(safe);
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Throwable safe = new AiProviderException("AI 服务暂不可用");
                onError.accept(safe);
                completion.completeExceptionally(safe);
                return;
            }

            response.body().subscribe(new Flow.Subscriber<>() {
                private final StringBuilder buffer = new StringBuilder();

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                    subscription.request(1);
                }

                @Override
                public void onNext(List<ByteBuffer> item) {
                    if (finished.get()) {
                        return;
                    }
                    try {
                        for (ByteBuffer source : item) {
                            ByteBuffer copy = source.asReadOnlyBuffer();
                            byte[] bytes = new byte[copy.remaining()];
                            copy.get(bytes);
                            buffer.append(new String(bytes, StandardCharsets.UTF_8));
                        }
                        consumeLines(buffer, onContent, onComplete, completion, finished, subscriptionRef);
                        if (!finished.get()) {
                            Flow.Subscription subscription = subscriptionRef.get();
                            if (subscription != null) {
                                subscription.request(1);
                            }
                        }
                    } catch (Exception ex) {
                        Throwable safe = providerFailure(ex);
                        onError.accept(safe);
                        finishExceptionally(completion, finished, subscriptionRef, safe);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    Throwable safe = providerFailure(throwable);
                    onError.accept(safe);
                    finishExceptionally(completion, finished, subscriptionRef, safe);
                }

                @Override
                public void onComplete() {
                    if (finished.compareAndSet(false, true)) {
                        emitTrailingLine(buffer, onContent);
                        onComplete.run();
                        completion.complete(null);
                    }
                }
            });
        });

        return new AiStreamHandle(completion, () -> {
            finished.set(true);
            requestFuture.cancel(true);
            Flow.Subscription subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.cancel();
            }
            completion.completeExceptionally(new CancellationException("AI stream cancelled"));
        });
    }

    private void consumeLines(
            StringBuilder buffer,
            Consumer<String> onContent,
            Runnable onComplete,
            CompletableFuture<Void> completion,
            AtomicBoolean finished,
            AtomicReference<Flow.Subscription> subscriptionRef
    ) {
        int newline;
        while (!finished.get() && (newline = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, newline);
            buffer.delete(0, newline + 1);
            String data = line.trim();
            if (!data.startsWith("data:")) {
                continue;
            }
            data = data.substring("data:".length()).trim();
            if ("[DONE]".equals(data)) {
                if (finished.compareAndSet(false, true)) {
                    Flow.Subscription subscription = subscriptionRef.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    onComplete.run();
                    completion.complete(null);
                }
                return;
            }
            String content = responseParser.parseStreamContent(data);
            if (content != null && !content.isEmpty()) {
                onContent.accept(content);
            }
        }
    }

    private void emitTrailingLine(StringBuilder buffer, Consumer<String> onContent) {
        if (buffer.isEmpty()) {
            return;
        }
        String data = buffer.toString().trim();
        if (data.startsWith("data:")) {
            String content = responseParser.parseStreamContent(data.substring(5).trim());
            if (content != null && !content.isEmpty()) {
                onContent.accept(content);
            }
        }
    }

    private void finishExceptionally(
            CompletableFuture<Void> completion,
            AtomicBoolean finished,
            AtomicReference<Flow.Subscription> subscriptionRef,
            Throwable error
    ) {
        if (finished.compareAndSet(false, true)) {
            Flow.Subscription subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.cancel();
            }
            completion.completeExceptionally(error);
        }
    }

    private Throwable providerFailure(Throwable throwable) {
        if (throwable instanceof CancellationException) {
            return throwable;
        }
        return new AiProviderException("AI 服务暂不可用");
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("AI base URL 未配置");
        }
        return normalized;
    }

    public static final class AiProviderException extends RuntimeException {
        public AiProviderException(String message) {
            super(message);
        }
    }
}
