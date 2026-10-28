package com.example.demo.auth.service.otp;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.AuthCodeChannel;
import com.example.demo.auth.dto.AuthCodeFinishRequest;
import com.example.demo.auth.dto.CognitoAuthFlowResponse;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RestCognitoOtpClientTimeoutTest {
    @Test
    void posts_cognito_json_with_target_and_payload_and_parses_success() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestTarget = new AtomicReference<>();
        AtomicReference<String> requestContentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cognito", exchange -> {
            requestCount.incrementAndGet();
            requestMethod.set(exchange.getRequestMethod());
            requestTarget.set(exchange.getRequestHeaders().getFirst("X-Amz-Target"));
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"AuthenticationResult\":{\"AccessToken\":\"access-token\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            RestCognitoOtpClient client = new RestCognitoOtpClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/cognito",
                    "client-id",
                    "client-secret",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1));

            CognitoAuthFlowResponse response = client.respondToChallenge(
                    "EMAIL_OTP",
                    "demo@example.com",
                    Map.of("EMAIL_OTP_CODE", "123456"),
                    "session-token");

            assertThat(response.getAuthenticationResult().getAccessToken()).isEqualTo("access-token");
            assertThat(requestCount).hasValue(1);
            assertThat(requestMethod).hasValue("POST");
            assertThat(requestTarget).hasValue("AWSCognitoIdentityProviderService.RespondToAuthChallenge");
            assertThat(requestContentType).hasValue("application/x-amz-json-1.1");
            assertThat(requestBody).hasValueSatisfying(body -> {
                assertThat(body).contains("\"ChallengeName\":\"EMAIL_OTP\"");
                assertThat(body).contains("\"ClientId\":\"client-id\"");
                assertThat(body).contains("\"Session\":\"session-token\"");
                assertThat(body).contains("\"EMAIL_OTP_CODE\":\"123456\"");
                assertThat(body).contains("\"USERNAME\":\"demo@example.com\"");
                assertThat(body).contains("\"SECRET_HASH\"");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalled_503_response_is_bounded_and_mapped_to_service_unavailable_without_retry() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch responseHeadersSent = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/cognito", exchange -> {
            requestCount.incrementAndGet();
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(503, 0);
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
            RestCognitoOtpClient client = new RestCognitoOtpClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/cognito",
                    "client-id",
                    "client-secret",
                    Duration.ofSeconds(1),
                    Duration.ofMillis(100));
            CognitoOtpAuthService service = new CognitoOtpAuthService(
                    client,
                    mock(UserRepository.class),
                    mock(UserProfileRepository.class),
                    mock(PasswordEncoder.class),
                    mock(JwtTokenProvider.class),
                    AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class)),
                    300,
                    300,
                    60);

            long start = System.nanoTime();
            Future<Throwable> call = callExecutor.submit(() -> {
                try {
                    service.finish(finishEmailRequest());
                    return null;
                } catch (Throwable exception) {
                    return exception;
                }
            });

            assertThat(responseHeadersSent.await(1, TimeUnit.SECONDS)).isTrue();
            Throwable failure = call.get(2, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(exception.getReason()).isEqualTo("邮箱验证码服务暂不可用，请稍后重试");
            });
            assertThat(requestCount).hasValue(1);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        } finally {
            releaseResponse.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
            callExecutor.shutdownNow();
            assertThat(serverExecutor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            assertThat(callExecutor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void disconnected_response_is_unavailable_without_retrying_the_request() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cognito", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        });
        server.start();

        try {
            RestCognitoOtpClient client = new RestCognitoOtpClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/cognito",
                    "client-id",
                    "client-secret",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1));

            assertThatThrownBy(() -> client.initiateUserAuth("demo@example.com", "EMAIL_OTP"))
                    .isInstanceOfSatisfying(CognitoOtpProviderException.class, exception -> {
                        assertThat(exception.isInvalidCode()).isFalse();
                        assertThat(exception).hasMessage("OTP_PROVIDER_UNAVAILABLE").hasNoCause();
                    });
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timeout_seam_rejects_values_below_one_millisecond_and_overflow() {
        Duration[] invalidTimeouts = {
                Duration.ZERO,
                Duration.ofMillis(-1),
                Duration.ofNanos(1),
                Duration.ofMillis(Integer.MAX_VALUE).plusMillis(1),
                Duration.ofSeconds(Long.MAX_VALUE)
        };
        for (Duration timeout : invalidTimeouts) {
            assertThatThrownBy(() -> new RestCognitoOtpClient(
                    "http://127.0.0.1:1/cognito", "client-id", "client-secret",
                    timeout, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RestCognitoOtpClient(
                    "http://127.0.0.1:1/cognito", "client-id", "client-secret",
                    Duration.ofSeconds(1), timeout))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThatCode(() -> new RestCognitoOtpClient(
                "http://127.0.0.1:1/cognito", "client-id", "client-secret",
                Duration.ofMillis(Integer.MAX_VALUE), Duration.ofMillis(Integer.MAX_VALUE)))
                .doesNotThrowAnyException();
    }

    @Test
    void spring_context_resolves_the_public_value_constructor() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "app.auth.cognito.region=ap-southeast-1",
                        "app.auth.cognito.app-client-id=context-client",
                        "app.auth.cognito.app-client-secret=context-secret")
                .withBean(RestCognitoOtpClient.class)
                .run(context -> assertThat(context).hasSingleBean(RestCognitoOtpClient.class));
    }

    private static AuthCodeFinishRequest finishEmailRequest() {
        AuthCodeFinishRequest request = new AuthCodeFinishRequest();
        request.setChannel(AuthCodeChannel.email);
        request.setAddress("demo@example.com");
        request.setCode("123456");
        request.setSession("session-token");
        return request;
    }
}
