package com.example.demo.ai.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealAiSummaryTransportTest {
    @Test
    void oversizedMetadataCannotReachNarrowDatabaseFields() throws Exception {
        HttpServer server = server("{\"id\":\"" + "x".repeat(129)
                + "\",\"choices\":[{\"message\":{\"content\":\"summary\"}}]}", 200);
        try {
            assertThat(failure(provider(server, Duration.ofSeconds(2)).requestSummary("content", 512)).code())
                    .isEqualTo(AiSummaryFailureCode.INVALID_RESPONSE);
        } finally { server.stop(0); }
    }

    @Test
    void successfulSummaryRequiresRealContentAndHttpFailureIsNotSuccessfulText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean fail = request.contains("failure-input");
            byte[] body = (fail
                    ? "sensitive-provider-error"
                    : "{\"choices\":[{\"message\":{\"content\":\"actual summary\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var provider = provider(server, Duration.ofSeconds(2));
            assertThat(provider.generateSummary("good-input")).isEqualTo("actual summary");
            assertThatThrownBy(() -> provider.generateSummary("failure-input"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AI summary generation failed")
                    .hasNoCause();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void summaryIncludesUsageAndProviderIdentifiersWithoutUsingChatRolePrompt() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"id\":\"response-42\",\"model\":\"returned-model\","
                    + "\"choices\":[{\"message\":{\"content\":\"actual summary\"}}],"
                    + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("x-request-id", "request-17");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var provider = new RealAiProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-only", "request-model", Duration.ofSeconds(2));
            String longInput = "prefix-" + "x".repeat(5_000) + "-full-tail";
            AiSummaryResult result = provider.requestSummary(longInput, 2_048)
                    .completion().get(2, TimeUnit.SECONDS);

            assertThat(result.summary()).isEqualTo("actual summary");
            assertThat(result.provider()).isEqualTo("127.0.0.1");
            assertThat(result.model()).isEqualTo("request-model");
            assertThat(result.responseModel()).isEqualTo("returned-model");
            assertThat(result.providerRequestId()).isEqualTo("request-17");
            assertThat(result.providerResponseId()).isEqualTo("response-42");
            assertThat(result.inputTokens()).isEqualTo(12L);
            assertThat(result.outputTokens()).isEqualTo(7L);
            assertThat(result.durationMillis()).isGreaterThanOrEqualTo(0L);
            assertThat(requestBody.get()).contains("-full-tail")
                    .contains("neutral summarization assistant")
                    .doesNotContain("sakiko");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingUsageIsUnknownAndNegativeUsageIsInvalidResponse() throws Exception {
        for (String responseBody : new String[]{
                "{\"choices\":[{\"message\":{\"content\":\"summary\"}}]}",
                "{\"choices\":[{\"message\":{\"content\":\"summary\"}}],"
                        + "\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":2}}"
        }) {
            HttpServer server = server(responseBody, 200);
            try {
                var provider = provider(server, Duration.ofSeconds(2));
                AiSummaryCall call = provider.requestSummary("content", 512);
                if (responseBody.contains("-1")) {
                    AiSummaryException failure = failure(call);
                    assertThat(failure.code()).isEqualTo(AiSummaryFailureCode.INVALID_RESPONSE);
                    assertThat(failure).hasNoCause();
                } else {
                    AiSummaryResult result = call.completion().get(2, TimeUnit.SECONDS);
                    assertThat(result.inputTokens()).isNull();
                    assertThat(result.outputTokens()).isNull();
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void emptyAndMalformedResponsesCannotCompleteSuccessfully() throws Exception {
        for (String responseBody : new String[]{
                "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"content\":\" \"}}]}",
                "not-json"
        }) {
            HttpServer server = server(responseBody, 200);
            try {
                AiSummaryException failure = failure(provider(server, Duration.ofSeconds(2))
                        .requestSummary("content", 512));
                assertThat(failure.code()).isIn(
                        AiSummaryFailureCode.EMPTY_RESPONSE,
                        AiSummaryFailureCode.INVALID_RESPONSE);
                assertThat(failure).hasMessage("AI summary generation failed").hasNoCause();
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void httpStatusFailuresHaveStableCategories() throws Exception {
        int[][] statusCodes = {
                {401, 0}, {403, 0}, {400, 1}, {404, 1}, {422, 1},
                {408, 2}, {429, 3}, {500, 4}, {503, 4}, {418, 5}
        };
        AiSummaryFailureCode[] categories = {
                AiSummaryFailureCode.AUTH,
                AiSummaryFailureCode.INVALID_REQUEST,
                AiSummaryFailureCode.TIMEOUT,
                AiSummaryFailureCode.RATE_LIMIT,
                AiSummaryFailureCode.UPSTREAM,
                AiSummaryFailureCode.INVALID_RESPONSE
        };
        for (int[] expected : statusCodes) {
            HttpServer server = server("provider-error-body", expected[0]);
            try {
                AiSummaryException failure = failure(provider(server, Duration.ofSeconds(2))
                        .requestSummary("content", 512));
                assertThat(failure.code()).isEqualTo(categories[expected[1]]);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void blockedResponseHasAnActualRequestDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                java.lang.Thread.sleep(600);
            } catch (InterruptedException ex) {
                java.lang.Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            var provider = new RealAiProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-only", "test", Duration.ofMillis(100));
            long start = System.nanoTime();
            AiSummaryException failure = failure(provider.requestSummary("body", 512));
            assertThat(failure.code()).isEqualTo(AiSummaryFailureCode.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancelStopsReadingAnOpenResponse() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch prefixFlushed = new CountDownLatch(1);
        CountDownLatch allowProbe = new CountDownLatch(1);
        CountDownLatch probeStarted = new CountDownLatch(1);
        AtomicBoolean connectionClosed = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestStarted.countDown();
            try {
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write("{\"choices\":[".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    prefixFlushed.countDown();
                    if (!allowProbe.await(2, TimeUnit.SECONDS)) {
                        return;
                    }
                    probeStarted.countDown();
                    while (true) {
                        output.write(new byte[8_192]);
                        output.flush();
                    }
                }
            } catch (IOException ex) {
                connectionClosed.set(true);
            } catch (InterruptedException ex) {
                java.lang.Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            var provider = provider(server, Duration.ofSeconds(10));
            AiSummaryCall call = provider.requestSummary("content", 512);
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(prefixFlushed.await(2, TimeUnit.SECONDS)).isTrue();
            call.cancel();
            AiSummaryException failure = failure(call);
            assertThat(failure.code()).isEqualTo(AiSummaryFailureCode.CANCELLED);
            allowProbe.countDown();
            assertThat(probeStarted.await(2, TimeUnit.SECONDS))
                    .as("cancel should allow a post-cancel socket probe").isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!connectionClosed.get() && System.nanoTime() < deadline) {
                java.lang.Thread.sleep(10);
            }
            assertThat(connectionClosed).as("cancel should close the response transport").isTrue();
        } finally {
            allowProbe.countDown();
            server.stop(0);
        }
    }

    @Test
    void responseBodyIsLimitedWhileReceiving() throws Exception {
        HttpServer server = server("x".repeat(128 * 1024 + 1), 200);
        try {
            AiSummaryException failure = failure(provider(server, Duration.ofSeconds(2))
                    .requestSummary("content", 512));
            assertThat(failure.code()).isEqualTo(AiSummaryFailureCode.RESPONSE_TOO_LARGE);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unsafeProviderUrisAreRejectedWithoutEchoingTheUri() {
        for (String uri : new String[]{
                "http://user:secret@127.0.0.1:1234",
                "http://127.0.0.1:1234/path?secret=value",
                "http://127.0.0.1:1234/path#secret"
        }) {
            assertThatThrownBy(() -> new RealAiProvider(uri, "key", "model", Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("AI base URL configuration is invalid")
                    .hasNoCause();
        }
    }

    @Test
    void requestBoundsFailBeforeTransport() {
        var provider = new RealAiProvider("http://127.0.0.1:1", "key", "model", Duration.ofSeconds(1));
        for (AiSummaryCall call : new AiSummaryCall[]{
                provider.requestSummary("x".repeat(12_001), 512),
                provider.requestSummary("content", 0),
                provider.requestSummary("content", 2_049)
        }) {
            AiSummaryException failure = failure(call);
            assertThat(failure.code()).isEqualTo(AiSummaryFailureCode.INVALID_REQUEST);
            assertThat(failure).hasMessage("AI summary generation failed").hasNoCause();
        }
    }

    private static HttpServer server(String body, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static RealAiProvider provider(HttpServer server, Duration timeout) {
        return new RealAiProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-only", "test", timeout);
    }

    private static AiSummaryException failure(AiSummaryCall call) {
        try {
            call.completion().join();
            throw new AssertionError("summary unexpectedly completed successfully");
        } catch (CompletionException ex) {
            assertThat(ex.getCause()).isInstanceOf(AiSummaryException.class);
            return (AiSummaryException) ex.getCause();
        }
    }
}
