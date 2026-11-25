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
class HomeOverviewAuthorizationEndpointTest {
    private static final String USER_A = "Bearer user-a";
    private static final String USER_B = "Bearer user-b";
    private static final String THREADS_URI = "/api/v1/threads?page=1&size=10";
    private static final String SECTIONS_URI = "/api/v1/sections?page=1&size=20";
    private static final String PROFILE_URI = "/api/v1/users/me";
    private static final String FOLLOWING_URI = "/api/v1/threads/following?page=1&size=4";
    private static final String NOTIFICATIONS_URI = "/api/v1/notifications?page=1&size=1&unread=true";
    private static final List<ObservedRequest> REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean NOTIFICATION_OUTAGE = new AtomicBoolean();
    private static final DisposableServer CORE = startCore();
    private static final DisposableServer NOTIFICATIONS = startNotifications();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("home.bff.enabled", () -> true);
        registry.add("home.bff.call-timeout", () -> "1s");
        registry.add("home.bff.public-cache-ttl", () -> "30s");
        registry.add("home.bff.core-base-url", () -> "http://127.0.0.1:" + CORE.port());
        registry.add("home.bff.notification-base-url", () -> "http://127.0.0.1:" + NOTIFICATIONS.port());
    }

    @BeforeEach
    void resetFixtures() {
        REQUESTS.clear();
        NOTIFICATION_OUTAGE.set(false);
    }

    @AfterAll
    static void stopDownstreams() {
        CORE.disposeNow();
        NOTIFICATIONS.disposeNow();
    }

    @Test
    void real_http_endpoint_scopes_personas_reuses_only_public_cache_and_recovers_after_notification_timeout()
            throws Exception {
        HttpResponse<String> userA = get(USER_A);
        assertThat(userA.statusCode()).isEqualTo(200);
        assertPrivateNoStore(userA);
        assertThat(userA.body()).contains(
                "\"threads\":{\"items\":[{\"id\":\"public-thread\"}",
                "\"sections\":{\"items\":[{\"id\":\"public-section\"}",
                "\"profile\":{\"id\":\"user-a\",\"displayName\":\"Alice\"}",
                "\"following\":{\"items\":[{\"id\":\"a-follow\"}",
                "\"unreadNotifications\":2",
                "\"unavailable\":[]");
        assertThat(userA.body()).doesNotContain("user-b", "Bob", "b-follow");

        HttpResponse<String> userB = get(USER_B);
        assertThat(userB.statusCode()).isEqualTo(200);
        assertPrivateNoStore(userB);
        assertThat(userB.body()).contains(
                "\"threads\":{\"items\":[{\"id\":\"public-thread\"}",
                "\"sections\":{\"items\":[{\"id\":\"public-section\"}",
                "\"profile\":{\"id\":\"user-b\",\"displayName\":\"Bob\"}",
                "\"following\":{\"items\":[{\"id\":\"b-follow\"}",
                "\"unreadNotifications\":7",
                "\"unavailable\":[]");
        assertThat(userB.body()).doesNotContain("user-a", "Alice", "a-follow", "\"unreadNotifications\":2");

        HttpResponse<String> anonymous = get(null);
        assertThat(anonymous.statusCode()).isEqualTo(200);
        assertThat(anonymous.headers().firstValue("Cache-Control")).isEmpty();
        assertThat(anonymous.body()).contains(
                "\"threads\":{\"items\":[{\"id\":\"public-thread\"}",
                "\"sections\":{\"items\":[{\"id\":\"public-section\"}",
                "\"profile\":null",
                "\"following\":null",
                "\"unreadNotifications\":null",
                "\"unavailable\":[]");
        assertThat(anonymous.body()).doesNotContain("user-a", "Alice", "user-b", "Bob", "a-follow", "b-follow");

        assertThat(requests(THREADS_URI)).hasSize(1);
        assertThat(requests(SECTIONS_URI)).hasSize(1);
        assertThat(requests(PROFILE_URI)).hasSize(2);
        assertThat(requests(FOLLOWING_URI)).hasSize(2);
        assertThat(requests(NOTIFICATIONS_URI)).hasSize(2);
        assertThat(requests(THREADS_URI)).allMatch(request -> request.authorization() == null);
        assertThat(requests(SECTIONS_URI)).allMatch(request -> request.authorization() == null);
        assertThat(requests(PROFILE_URI)).extracting(ObservedRequest::authorization)
                .containsExactlyInAnyOrder(USER_A, USER_B);
        assertThat(requests(FOLLOWING_URI)).extracting(ObservedRequest::authorization)
                .containsExactlyInAnyOrder(USER_A, USER_B);
        assertThat(requests(NOTIFICATIONS_URI)).extracting(ObservedRequest::authorization)
                .containsExactlyInAnyOrder(USER_A, USER_B);

        NOTIFICATION_OUTAGE.set(true);
        HttpResponse<String> timedOutNotification = get(USER_A);
        assertThat(timedOutNotification.statusCode()).isEqualTo(200);
        assertPrivateNoStore(timedOutNotification);
        assertThat(timedOutNotification.body()).contains(
                "\"profile\":{\"id\":\"user-a\",\"displayName\":\"Alice\"}",
                "\"following\":{\"items\":[{\"id\":\"a-follow\"}",
                "\"unreadNotifications\":null",
                "\"unavailable\":[\"unreadNotifications\"]");
        assertThat(timedOutNotification.body()).doesNotContain("user-b", "Bob", "b-follow");

        NOTIFICATION_OUTAGE.set(false);
        HttpResponse<String> recovered = get(USER_A);
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertPrivateNoStore(recovered);
        assertThat(recovered.body()).contains(
                "\"profile\":{\"id\":\"user-a\",\"displayName\":\"Alice\"}",
                "\"following\":{\"items\":[{\"id\":\"a-follow\"}",
                "\"unreadNotifications\":2",
                "\"unavailable\":[]");

        assertThat(requests(THREADS_URI)).hasSize(1);
        assertThat(requests(SECTIONS_URI)).hasSize(1);
        assertThat(requests(PROFILE_URI)).hasSize(4);
        assertThat(requests(FOLLOWING_URI)).hasSize(4);
        assertThat(requests(NOTIFICATIONS_URI)).hasSize(4);
    }

    private static void assertPrivateNoStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control")).contains("private, no-store");
    }

    private HttpResponse<String> get(String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/home/overview"))
                .timeout(Duration.ofSeconds(5));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HTTP_CLIENT.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<ObservedRequest> requests(String uri) {
        return REQUESTS.stream().filter(request -> request.uri().equals(uri)).toList();
    }

    private static DisposableServer startCore() {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    String uri = request.uri();
                    String authorization = request.requestHeaders().get("Authorization");
                    REQUESTS.add(new ObservedRequest(uri, authorization));
                    if (uri.equals(PROFILE_URI) && !USER_A.equals(authorization) && !USER_B.equals(authorization)) {
                        return response.status(401).send();
                    }
                    if (uri.equals(FOLLOWING_URI) && !USER_A.equals(authorization) && !USER_B.equals(authorization)) {
                        return response.status(401).send();
                    }
                    String body = switch (uri) {
                        case THREADS_URI ->
                                "{\"items\":[{\"id\":\"public-thread\"}],\"page\":1,\"size\":10,\"total\":1}";
                        case SECTIONS_URI ->
                                "{\"items\":[{\"id\":\"public-section\"}],\"page\":1,\"size\":20,\"total\":1}";
                        case PROFILE_URI -> USER_A.equals(authorization)
                                ? "{\"id\":\"user-a\",\"displayName\":\"Alice\"}"
                                : "{\"id\":\"user-b\",\"displayName\":\"Bob\"}";
                        case FOLLOWING_URI -> USER_A.equals(authorization)
                                ? "{\"items\":[{\"id\":\"a-follow\"}],\"page\":1,\"size\":4,\"total\":1}"
                                : "{\"items\":[{\"id\":\"b-follow\"}],\"page\":1,\"size\":4,\"total\":1}";
                        default -> "{}";
                    };
                    return response.header("Content-Type", "application/json")
                            .sendString(Mono.just(body));
                }).bindNow();
    }

    private static DisposableServer startNotifications() {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    String uri = request.uri();
                    String authorization = request.requestHeaders().get("Authorization");
                    REQUESTS.add(new ObservedRequest(uri, authorization));
                    if (NOTIFICATION_OUTAGE.get()) {
                        return response.header("Content-Type", "application/json")
                                .sendString(Mono.delay(Duration.ofSeconds(2))
                                        .map(ignored -> "{\"total\":99}"));
                    }
                    String body = USER_B.equals(authorization) ? "{\"total\":7}" : "{\"total\":2}";
                    return response.header("Content-Type", "application/json")
                            .sendString(Mono.just(body));
                }).bindNow();
    }

    private record ObservedRequest(String uri, String authorization) {
    }
}
