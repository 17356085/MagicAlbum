package com.example.demo.auth.service.otp;

import com.example.demo.auth.dto.TurnstileSiteVerifyResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareTurnstileVerifyClientTimeoutTest {
    @Test
    void posts_form_and_parses_success_response() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestContentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verify", exchange -> {
            requestCount.incrementAndGet();
            requestMethod.set(exchange.getRequestMethod());
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"success\":true,\"hostname\":\"localhost\",\"action\":\"login\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            CloudflareTurnstileVerifyClient client = new CloudflareTurnstileVerifyClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/verify",
                    "test-secret",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1));

            TurnstileSiteVerifyResponse response = client.verify("test-token");

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getHostname()).isEqualTo("localhost");
            assertThat(response.getAction()).isEqualTo("login");
            assertThat(requestCount).hasValue(1);
            assertThat(requestMethod).hasValue("POST");
            assertThat(requestContentType).hasValue("application/x-www-form-urlencoded");
            assertThat(formValues(requestBody.get()))
                    .containsEntry("secret", "test-secret")
                    .containsEntry("response", "test-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalled_response_is_bounded_and_auth_verify_maps_timeout_to_service_unavailable() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch responseHeadersSent = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/verify", exchange -> {
            requestCount.incrementAndGet();
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                responseHeadersSent.countDown();
                try {
                    releaseResponse.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        ExecutorService callExecutor = Executors.newSingleThreadExecutor();
        try {
            CloudflareTurnstileVerifyClient client = new CloudflareTurnstileVerifyClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/verify",
                    "test-secret",
                    Duration.ofSeconds(1),
                    Duration.ofMillis(100));
            AuthVerifyService service = AuthVerifyService.forTest("turnstile", "", client);
            long start = System.nanoTime();
            Future<ResponseStatusException> call = callExecutor.submit(() -> {
                try {
                    service.verify("test-token", "turnstile", "login", "login");
                    throw new AssertionError("stalled Turnstile verification unexpectedly succeeded");
                } catch (ResponseStatusException exception) {
                    return exception;
                }
            });

            assertThat(responseHeadersSent.await(1, TimeUnit.SECONDS)).isTrue();
            ResponseStatusException exception = call.get(2, TimeUnit.SECONDS);
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(exception.getReason()).isEqualTo("人工验证服务暂不可用");
            assertThat(requestCount).hasValue(1);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        } finally {
            releaseResponse.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
            callExecutor.shutdownNow();
            callExecutor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void disconnected_response_does_not_repeat_the_verification_request() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verify", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        });
        server.start();
        try {
            var client = new CloudflareTurnstileVerifyClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/verify", "test-secret",
                    Duration.ofSeconds(1), Duration.ofSeconds(1));
            var service = AuthVerifyService.forTest("turnstile", "", client);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.verify("test-token", "turnstile", "login", "login"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timeout_seam_rejects_unbounded_values() {
        for (Duration timeout : new Duration[]{
                Duration.ZERO,
                Duration.ofMillis(-1),
                Duration.ofNanos(1),
                Duration.ofMillis(Integer.MAX_VALUE).plusMillis(1)
        }) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CloudflareTurnstileVerifyClient(
                            "http://127.0.0.1:1/verify", "test-secret", timeout, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CloudflareTurnstileVerifyClient(
                            "http://127.0.0.1:1/verify", "test-secret", Duration.ofSeconds(1), timeout))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void spring_context_resolves_the_public_value_constructor() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "app.auth.verify.turnstile.verify-url=http://127.0.0.1:1/verify",
                        "app.auth.verify.turnstile.secret-key=context-secret")
                .withBean(CloudflareTurnstileVerifyClient.class)
                .run(context -> assertThat(context).hasSingleBean(CloudflareTurnstileVerifyClient.class));
    }

    private static Map<String, String> formValues(String body) {
        return java.util.Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        pair -> java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
