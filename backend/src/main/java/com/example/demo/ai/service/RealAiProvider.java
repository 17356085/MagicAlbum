package com.example.demo.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * OpenAI-compatible provider with a real asynchronous SSE transport.
 */
public class RealAiProvider implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(RealAiProvider.class);

    private static final int MAX_INPUT_CHARS = 12_000;
    private static final int MAX_OUTPUT_TOKENS = 2_048;
    private static final int MAX_SUMMARY_CHARS = 4_000;
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final long MAX_USAGE_TOKENS = 10_000_000L;
    private static final ScheduledThreadPoolExecutor SUMMARY_DEADLINES = createDeadlineExecutor();

    private final HttpClient httpClient;
    private final URI completionUri;
    private final String providerName;
    private final String apiKey;
    private final AiPromptBuilder promptBuilder;
    private final AiResponseParser responseParser;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public RealAiProvider(String baseUrl, String apiKey, String modelName, Duration timeout) {
        this(baseUrl, apiKey, modelName, timeout, null);
    }

    RealAiProvider(
            String baseUrl,
            String apiKey,
            String modelName,
            Duration timeout,
            HttpClient httpClient
    ) {
        URI baseUri = validateBaseUrl(baseUrl);
        this.timeout = safeTimeout(timeout);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        String safeModelName = modelName == null ? "" : modelName.trim();
        if (safeModelName.isBlank() || safeModelName.length() > 256 || baseUri.getHost().length() > 128) {
            throw new IllegalArgumentException("AI provider configuration is invalid");
        }
        this.promptBuilder = new AiPromptBuilder(safeModelName);
        this.responseParser = new AiResponseParser();
        this.objectMapper = new ObjectMapper();
        this.providerName = baseUri.getHost();
        this.completionUri = URI.create(baseUri.toString() + "/chat/completions");
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder().connectTimeout(this.timeout).build()
                : Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String generateSummary(String content) {
        AiSummaryCall call = requestSummary(content, 512);
        try {
            return call.completion().get().summary();
        } catch (InterruptedException ex) {
            call.cancel();
            java.lang.Thread.currentThread().interrupt();
            throw new AiSummaryException(AiSummaryFailureCode.CANCELLED);
        } catch (ExecutionException ex) {
            throw summaryFailure(ex.getCause());
        } catch (CancellationException ex) {
            throw new AiSummaryException(AiSummaryFailureCode.CANCELLED);
        } catch (RuntimeException ex) {
            throw summaryFailure(ex);
        }
    }

    @Override
    public AiSummaryCall requestSummary(String content, int maxOutputTokens) {
        if (content == null || content.isBlank() || content.length() > MAX_INPUT_CHARS
                || maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS) {
            return AiSummaryCall.failed(AiSummaryFailureCode.INVALID_REQUEST);
        }

        SummaryOperation operation = new SummaryOperation(content, maxOutputTokens);
        operation.start();
        return new AiSummaryCall(operation.completion, operation::cancel);
    }

    @Override
    public String summaryProviderName() {
        return providerName;
    }

    @Override
    public String summaryModelName() {
        return promptBuilder.getModelName();
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
                    if (finished.get()) {
                        subscription.cancel();
                        return;
                    }
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

    private AiSummaryException summaryFailure(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof AiSummaryException summaryException) {
            return summaryException;
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return new AiSummaryException(AiSummaryFailureCode.TIMEOUT);
        }
        if (cause instanceof CancellationException) {
            return new AiSummaryException(AiSummaryFailureCode.CANCELLED);
        }
        return new AiSummaryException(AiSummaryFailureCode.TRANSPORT);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class SummaryOperation {
        private final String content;
        private final int maxOutputTokens;
        private final long startedNanos = System.nanoTime();
        private final CompletableFuture<AiSummaryResult> completion = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<Flow.Subscription> bodySubscription = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>>> requestFuture =
                new AtomicReference<>();
        private volatile ScheduledFuture<?> deadline;

        private SummaryOperation(String content, int maxOutputTokens) {
            this.content = content;
            this.maxOutputTokens = maxOutputTokens;
        }

        private void start() {
            deadline = SUMMARY_DEADLINES.schedule(
                    () -> fail(AiSummaryFailureCode.TIMEOUT),
                    timeoutMillis(timeout),
                    TimeUnit.MILLISECONDS
            );

            try {
                String requestJson = objectMapper.writeValueAsString(Map.of(
                        "model", promptBuilder.getModelName(),
                        "messages", promptBuilder.buildSummaryMessages(content),
                        "temperature", 0.2,
                        "max_tokens", maxOutputTokens,
                        "stream", false
                ));
                HttpRequest request = HttpRequest.newBuilder(completionUri)
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                        .build();
                CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> future =
                        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher());
                requestFuture.set(future);
                if (finished.get()) future.cancel(true);
                future.whenComplete(this::handleResponse);
            } catch (Exception ex) {
                fail(summaryFailureCode(ex));
            }
        }

        private void handleResponse(
                HttpResponse<Flow.Publisher<List<ByteBuffer>>> response,
                Throwable error
        ) {
            if (error != null) {
                fail(summaryFailureCode(error));
                return;
            }
            if (response == null || response.body() == null) {
                fail(AiSummaryFailureCode.INVALID_RESPONSE);
                return;
            }
            response.body().subscribe(new LimitedBodySubscriber(response));
        }

        private AiSummaryFailureCode summaryFailureCode(Throwable throwable) {
            Throwable cause = unwrap(throwable);
            if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
                return AiSummaryFailureCode.TIMEOUT;
            }
            if (cause instanceof CancellationException) {
                return AiSummaryFailureCode.CANCELLED;
            }
            if (cause instanceof IllegalArgumentException) {
                return AiSummaryFailureCode.INVALID_REQUEST;
            }
            return AiSummaryFailureCode.TRANSPORT;
        }

        private void completeResponse(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response, byte[] body) {
            try {
                AiSummaryFailureCode statusFailure = httpStatusFailure(response.statusCode());
                if (statusFailure != null) {
                    fail(statusFailure);
                    return;
                }
                AiSummaryResult result = parseResponse(response, body);
                complete(result);
            } catch (AiSummaryException ex) {
                fail(ex.code());
            } catch (RuntimeException ex) {
                fail(AiSummaryFailureCode.INVALID_RESPONSE);
            }
        }

        private AiSummaryResult parseResponse(
                HttpResponse<Flow.Publisher<List<ByteBuffer>>> response,
                byte[] body
        ) {
            if (body.length == 0) {
                throw new AiSummaryException(AiSummaryFailureCode.EMPTY_RESPONSE);
            }

            JsonNode root;
            try {
                root = objectMapper.readTree(body);
            } catch (IOException ex) {
                throw new AiSummaryException(AiSummaryFailureCode.INVALID_RESPONSE);
            }
            if (root == null || !root.isObject()) {
                throw new AiSummaryException(AiSummaryFailureCode.INVALID_RESPONSE);
            }

            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray()) {
                throw new AiSummaryException(AiSummaryFailureCode.INVALID_RESPONSE);
            }
            if (choices.isEmpty()) {
                throw new AiSummaryException(AiSummaryFailureCode.EMPTY_RESPONSE);
            }
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice == null ? null : firstChoice.get("message");
            JsonNode contentNode = message == null ? null : message.get("content");
            if (message == null || !message.isObject() || contentNode == null
                    || !contentNode.isTextual()) {
                throw new AiSummaryException(AiSummaryFailureCode.INVALID_RESPONSE);
            }
            String summary = contentNode.textValue();
            if (summary == null || summary.isBlank()) {
                throw new AiSummaryException(AiSummaryFailureCode.EMPTY_RESPONSE);
            }
            if (summary.length() > MAX_SUMMARY_CHARS) {
                throw new AiSummaryException(AiSummaryFailureCode.RESPONSE_TOO_LARGE);
            }

            String responseId = optionalText(root, "id", 128);
            String responseModel = optionalText(root, "model", 256);
            JsonNode usage = root.get("usage");
            Long inputTokens = usageTokens(usage, "prompt_tokens");
            Long outputTokens = usageTokens(usage, "completion_tokens");
            String requestId = response.headers().firstValue("x-request-id").orElse(null);
            if (requestId != null && requestId.length() > 128) throw new AiSummaryException(AiSummaryFailureCode.INVALID_RESPONSE);
            return new AiSummaryResult(
                    summary,
                    providerName,
                    promptBuilder.getModelName(),
                    responseModel,
                    requestId,
                    responseId,
                    inputTokens,
                    outputTokens,
                    elapsedMillis(startedNanos)
            );
        }

        private String optionalText(JsonNode root, String fieldName, int maxLength) {
            JsonNode value = root.get(fieldName);
            if (value == null || value.isNull()) {
                return null;
            }
            if (!value.isTextual() || value.textValue().length() > maxLength) {
                throw new AiSummaryException(AiSummaryFailureCode.INVALID_RESPONSE);
            }
            return value.textValue();
        }

        private Long usageTokens(JsonNode usage, String fieldName) {
            if (usage == null || usage.isNull()) {
                return null;
            }
            if (!usage.isObject()) {
                throw new AiSummaryException(AiSummaryFailureCode.INVALID_RESPONSE);
            }
            JsonNode value = usage.get(fieldName);
            if (value == null || value.isNull()) {
                return null;
            }
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new AiSummaryException(AiSummaryFailureCode.INVALID_RESPONSE);
            }
            long tokens = value.longValue();
            if (tokens < 0 || tokens > MAX_USAGE_TOKENS) {
                throw new AiSummaryException(AiSummaryFailureCode.INVALID_RESPONSE);
            }
            return tokens;
        }

        private void complete(AiSummaryResult result) {
            if (finished.compareAndSet(false, true)) {
                cancelDeadline();
                completion.complete(result);
            }
        }

        private void fail(AiSummaryFailureCode code) {
            if (finished.compareAndSet(false, true)) {
                cancelTransport();
                cancelDeadline();
                log.warn("ai_summary_failed code={} provider={}", code, providerName);
                completion.completeExceptionally(new AiSummaryException(code));
            }
        }

        private void cancel() {
            fail(AiSummaryFailureCode.CANCELLED);
        }

        private void cancelTransport() {
            CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> future = requestFuture.get();
            if (future != null) {
                future.cancel(true);
            }
            Flow.Subscription subscription = bodySubscription.get();
            if (subscription != null) {
                subscription.cancel();
            }
        }

        private void cancelDeadline() {
            ScheduledFuture<?> scheduled = deadline;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }

        private final class LimitedBodySubscriber implements Flow.Subscriber<List<ByteBuffer>> {
            private final HttpResponse<Flow.Publisher<List<ByteBuffer>>> response;
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();
            private int bytes;

            private LimitedBodySubscriber(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
                this.response = response;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                bodySubscription.set(subscription);
                if (finished.get()) {
                    subscription.cancel();
                    return;
                }
                subscription.request(1);
            }

            @Override
            public void onNext(List<ByteBuffer> item) {
                if (finished.get()) {
                    return;
                }
                try {
                    for (ByteBuffer source : item) {
                        if (source == null) {
                            fail(AiSummaryFailureCode.INVALID_RESPONSE);
                            return;
                        }
                        ByteBuffer copy = source.asReadOnlyBuffer();
                        int remaining = copy.remaining();
                        if ((long) bytes + remaining > MAX_RESPONSE_BYTES) {
                            fail(AiSummaryFailureCode.RESPONSE_TOO_LARGE);
                            return;
                        }
                        byte[] chunk = new byte[remaining];
                        copy.get(chunk);
                        body.writeBytes(chunk);
                        bytes += remaining;
                    }
                    if (!finished.get()) {
                        Flow.Subscription subscription = bodySubscription.get();
                        if (subscription != null) {
                            subscription.request(1);
                        }
                    }
                } catch (RuntimeException ex) {
                    fail(summaryFailureCode(ex));
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (!finished.get()) {
                    fail(summaryFailureCode(throwable));
                }
            }

            @Override
            public void onComplete() {
                if (!finished.get()) {
                    completeResponse(response, body.toByteArray());
                }
            }
        }
    }

    private static AiSummaryFailureCode httpStatusFailure(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> AiSummaryFailureCode.AUTH;
            case 400, 404, 422 -> AiSummaryFailureCode.INVALID_REQUEST;
            case 408 -> AiSummaryFailureCode.TIMEOUT;
            case 429 -> AiSummaryFailureCode.RATE_LIMIT;
            default -> statusCode >= 500 && statusCode <= 599
                    ? AiSummaryFailureCode.UPSTREAM
                    : (statusCode >= 200 && statusCode < 300
                    ? null : AiSummaryFailureCode.INVALID_RESPONSE);
        };
    }

    private static URI validateBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("AI base URL configuration is invalid");
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("AI base URL configuration is invalid");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("AI base URL configuration is invalid");
        }
    }

    private static Duration safeTimeout(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            return Duration.ofSeconds(30);
        }
        return value.compareTo(Duration.ofSeconds(120)) > 0 ? Duration.ofSeconds(120) : value;
    }

    private static long timeoutMillis(Duration value) {
        try {
            return Math.max(1L, value.toMillis());
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private static ScheduledThreadPoolExecutor createDeadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            java.lang.Thread thread = new java.lang.Thread(runnable, "ai-summary-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public static final class AiProviderException extends RuntimeException {
        public AiProviderException(String message) {
            super(message);
        }
    }
}
