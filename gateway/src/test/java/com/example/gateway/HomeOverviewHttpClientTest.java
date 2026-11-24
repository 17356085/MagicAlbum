package com.example.gateway;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeOverviewHttpClientTest {
    @Test
    void forwards_authorization_only_to_personal_resources_and_uses_fixed_paths() {
        List<ObservedRequest> coreRequests = new CopyOnWriteArrayList<>();
        List<ObservedRequest> notificationRequests = new CopyOnWriteArrayList<>();
        DisposableServer core = startServer(coreRequests, false);
        DisposableServer notifications = startServer(notificationRequests, true);
        try {
            HomeBffProperties properties = properties(core, notifications);
            HomeOverviewHttpClient client = new HomeOverviewHttpClient(WebClient.builder(), properties);

            HomeOverviewUpstreamResponse threads = client.fetch(HomeOverviewResource.THREADS, "Bearer user-a").block();
            HomeOverviewUpstreamResponse profile = client.fetch(HomeOverviewResource.PROFILE, "Bearer user-a").block();
            HomeOverviewUpstreamResponse notificationsResponse = client.fetch(
                    HomeOverviewResource.UNREAD_NOTIFICATIONS, "Bearer user-a").block();
            assertThat(threads.hasPayload()).isTrue();
            assertThat(profile.hasPayload()).isTrue();
            assertThat(notificationsResponse.hasPayload()).isTrue();

            assertThat(coreRequests).extracting(ObservedRequest::uri)
                    .containsExactlyInAnyOrder(
                            "/api/v1/threads?page=1&size=10",
                            "/api/v1/users/me");
            assertThat(notificationRequests).extracting(ObservedRequest::uri)
                    .containsExactly("/api/v1/notifications?page=1&size=1&unread=true");
            assertThat(coreRequests.stream()
                    .filter(request -> request.uri().equals("/api/v1/threads?page=1&size=10"))
                    .findFirst().orElseThrow().authorization()).isNull();
            assertThat(coreRequests.stream()
                    .filter(request -> request.uri().equals("/api/v1/users/me"))
                    .findFirst().orElseThrow().authorization()).isEqualTo("Bearer user-a");
            assertThat(notificationRequests.getFirst().authorization()).isEqualTo("Bearer user-a");
        } finally {
            core.disposeNow();
            notifications.disposeNow();
        }
    }

    @Test
    void rejects_non_http_or_load_balancer_upstream_origins() {
        HomeBffProperties properties = new HomeBffProperties();
        properties.setCoreBaseUrl(URI.create("file:///tmp/bluealbum-core"));
        properties.setNotificationBaseUrl(URI.create("http://127.0.0.1:8081"));

        assertThatThrownBy(() -> new HomeOverviewHttpClient(WebClient.builder(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("home.bff.core-base-url");
    }

    private static DisposableServer startServer(List<ObservedRequest> requests, boolean notification) {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    requests.add(new ObservedRequest(
                            request.uri(), request.requestHeaders().get("Authorization")));
                    String body = notification ? "{\"total\":7}" : "{\"source\":\"core\"}";
                    return response.header("Content-Type", "application/json")
                            .sendString(Mono.just(body));
                })
                .bindNow();
    }

    private static HomeBffProperties properties(DisposableServer core, DisposableServer notifications) {
        HomeBffProperties properties = new HomeBffProperties();
        properties.setCoreBaseUrl(URI.create("http://127.0.0.1:" + core.port()));
        properties.setNotificationBaseUrl(URI.create("http://127.0.0.1:" + notifications.port()));
        return properties;
    }

    private record ObservedRequest(String uri, String authorization) {
    }
}
