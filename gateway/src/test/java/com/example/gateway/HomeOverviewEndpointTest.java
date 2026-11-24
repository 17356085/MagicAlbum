package com.example.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HomeOverviewEndpointTest {
    private static final List<ObservedRequest> REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean PROFILE_UNAUTHORIZED = new AtomicBoolean();
    private static final DisposableServer CORE = startCore();
    private static final DisposableServer NOTIFICATIONS = startNotifications();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("home.bff.enabled", () -> true);
        registry.add("home.bff.call-timeout", () -> "1s");
        registry.add("home.bff.public-cache-ttl", () -> "0s");
        registry.add("home.bff.core-base-url", () -> "http://127.0.0.1:" + CORE.port());
        registry.add("home.bff.notification-base-url", () -> "http://127.0.0.1:" + NOTIFICATIONS.port());
    }

    @BeforeEach
    void resetRequests() {
        REQUESTS.clear();
        PROFILE_UNAUTHORIZED.set(false);
    }

    @AfterAll
    static void stopDownstreams() {
        CORE.disposeNow();
        NOTIFICATIONS.disposeNow();
    }

    @Test
    void anonymous_get_returns_typed_envelope_without_personal_requests() throws Exception {
        HttpResponse<String> response = get(null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "\"threads\":{\"items\":[],\"page\":1,\"size\":10,\"total\":0}",
                "\"sections\":{\"items\":[],\"page\":1,\"size\":20,\"total\":0}",
                "\"profile\":null",
                "\"following\":null",
                "\"unreadNotifications\":null",
                "\"unavailable\":[]");
        assertThat(REQUESTS).extracting(ObservedRequest::uri)
                .containsExactlyInAnyOrder(
                        "/api/v1/threads?page=1&size=10",
                        "/api/v1/sections?page=1&size=20");
    }

    @Test
    void authenticated_get_composes_personal_fields_with_forwarded_authorization() throws Exception {
        HttpResponse<String> response = get("Bearer user-a");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "\"profile\":{\"id\":1,\"displayName\":\"A\"}",
                "\"following\":{\"items\":[],\"page\":1,\"size\":4,\"total\":0}",
                "\"unreadNotifications\":4");
        assertThat(response.headers().firstValue("Cache-Control")).contains("private, no-store");
        assertThat(REQUESTS).allMatch(request -> request.authorization() == null
                || request.authorization().equals("Bearer user-a"));
        assertThat(REQUESTS.stream()
                .filter(request -> request.uri().startsWith("/api/v1/threads?page=1"))
                .findFirst().orElseThrow().authorization()).isNull();
        assertThat(REQUESTS.stream()
                .filter(request -> request.uri().equals("/api/v1/users/me"))
                .findFirst().orElseThrow().authorization()).isEqualTo("Bearer user-a");
    }

    @Test
    void profile_authentication_failure_is_returned_without_an_envelope() throws Exception {
        PROFILE_UNAUTHORIZED.set(true);

        HttpResponse<String> response = get("Bearer expired");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("private, no-store");
    }

    private HttpResponse<String> get(String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/home/overview"))
                .timeout(Duration.ofSeconds(5));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static DisposableServer startCore() {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    String uri = request.uri();
                    REQUESTS.add(new ObservedRequest(uri, request.requestHeaders().get("Authorization")));
                    if (uri.equals("/api/v1/users/me") && PROFILE_UNAUTHORIZED.get()) {
                        return response.status(401).send();
                    }
                    String body = switch (uri) {
                        case "/api/v1/threads?page=1&size=10" ->
                                "{\"items\":[],\"page\":1,\"size\":10,\"total\":0}";
                        case "/api/v1/sections?page=1&size=20" ->
                                "{\"items\":[],\"page\":1,\"size\":20,\"total\":0}";
                        case "/api/v1/threads/following?page=1&size=4" ->
                                "{\"items\":[],\"page\":1,\"size\":4,\"total\":0}";
                        case "/api/v1/users/me" -> "{\"id\":1,\"displayName\":\"A\"}";
                        default -> "{}";
                    };
                    return response.header("Content-Type", "application/json")
                            .sendString(Mono.just(body));
                }).bindNow();
    }

    private static DisposableServer startNotifications() {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    REQUESTS.add(new ObservedRequest(
                            request.uri(), request.requestHeaders().get("Authorization")));
                    return response.header("Content-Type", "application/json")
                            .sendString(Mono.just(
                                    "{\"items\":[],\"page\":1,\"size\":1,\"total\":4,\"source\":\"stub\"}"));
                }).bindNow();
    }

    private record ObservedRequest(String uri, String authorization) {
    }
}
