package com.example.gateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

class HomeOverviewServiceTest {
    @Test
    void composes_existing_payloads_and_scopes_authorization_to_personal_calls() {
        RecordingClient client = new RecordingClient();
        client.success(HomeOverviewResource.THREADS, payload("threads"));
        client.success(HomeOverviewResource.SECTIONS, payload("sections"));
        client.success(HomeOverviewResource.PROFILE, payload("profile"));
        client.success(HomeOverviewResource.FOLLOWING, payload("following"));
        client.success(HomeOverviewResource.UNREAD_NOTIFICATIONS, notificationPayload(7));

        HomeOverviewResponse response = new HomeOverviewService(client, properties()).compose("Bearer user-a")
                .block().body();

        assertThat(response.threads().get("value").asText()).isEqualTo("threads");
        assertThat(response.sections().get("value").asText()).isEqualTo("sections");
        assertThat(response.profile().get("value").asText()).isEqualTo("profile");
        assertThat(response.following().get("value").asText()).isEqualTo("following");
        assertThat(response.unreadNotifications()).isEqualTo(7L);
        assertThat(response.unavailable()).isEmpty();
        assertThat(client.calls()).containsExactlyInAnyOrder(
                new Call(HomeOverviewResource.THREADS, null),
                new Call(HomeOverviewResource.SECTIONS, null),
                new Call(HomeOverviewResource.PROFILE, "Bearer user-a"),
                new Call(HomeOverviewResource.FOLLOWING, "Bearer user-a"),
                new Call(HomeOverviewResource.UNREAD_NOTIFICATIONS, "Bearer user-a"));
    }

    @Test
    void skips_all_personal_requests_for_anonymous_callers() {
        RecordingClient client = new RecordingClient();
        client.success(HomeOverviewResource.THREADS, payload("threads"));
        client.success(HomeOverviewResource.SECTIONS, payload("sections"));

        HomeOverviewResponse response = new HomeOverviewService(client, properties()).compose(null)
                .block().body();

        assertThat(response.profile()).isNull();
        assertThat(response.following()).isNull();
        assertThat(response.unreadNotifications()).isNull();
        assertThat(response.unavailable()).isEmpty();
        assertThat(client.calls()).containsExactlyInAnyOrder(
                new Call(HomeOverviewResource.THREADS, null),
                new Call(HomeOverviewResource.SECTIONS, null));
    }

    @Test
    void propagates_authoritative_profile_unauthorized_without_personal_data() {
        RecordingClient client = new RecordingClient();
        client.success(HomeOverviewResource.THREADS, payload("threads"));
        client.success(HomeOverviewResource.SECTIONS, payload("sections"));
        client.response(HomeOverviewResource.PROFILE, new HomeOverviewUpstreamResponse(401, null));
        client.success(HomeOverviewResource.FOLLOWING, payload("following"));
        client.success(HomeOverviewResource.UNREAD_NOTIFICATIONS, notificationPayload(2));

        HomeOverviewService.CompositionResult result = new HomeOverviewService(client, properties())
                .compose("Bearer expired").block();

        assertThat(result.status().value()).isEqualTo(401);
        assertThat(result.body()).isNull();
    }

    @Test
    void keeps_successful_fields_when_transient_calls_fail() {
        RecordingClient client = new RecordingClient();
        client.success(HomeOverviewResource.THREADS, payload("threads"));
        client.response(HomeOverviewResource.SECTIONS, new HomeOverviewUpstreamResponse(503, null));
        client.success(HomeOverviewResource.PROFILE, payload("profile"));
        client.response(HomeOverviewResource.FOLLOWING, HomeOverviewUpstreamResponse.failure());
        client.success(HomeOverviewResource.UNREAD_NOTIFICATIONS, notificationPayload(3));

        HomeOverviewResponse response = new HomeOverviewService(client, properties()).compose("Bearer user-a")
                .block().body();

        assertThat(response.threads().get("value").asText()).isEqualTo("threads");
        assertThat(response.sections()).isNull();
        assertThat(response.profile().get("value").asText()).isEqualTo("profile");
        assertThat(response.following()).isNull();
        assertThat(response.unreadNotifications()).isEqualTo(3L);
        assertThat(response.unavailable()).containsExactly("sections", "following");
    }

    @Test
    void starts_public_and_personal_calls_concurrently() {
        RecordingClient client = new RecordingClient();
        client.delay = Duration.ofMillis(40);
        for (HomeOverviewResource resource : HomeOverviewResource.values()) {
            client.success(resource, resource == HomeOverviewResource.UNREAD_NOTIFICATIONS
                    ? notificationPayload(1) : payload(resource.fieldName()));
        }

        new HomeOverviewService(client, properties()).compose("Bearer user-a").block();

        assertThat(client.maximumActive()).isEqualTo(5);
    }

    @Test
    void caches_public_snapshot_but_never_reuses_personalized_results() {
        RecordingClient client = new RecordingClient();
        for (HomeOverviewResource resource : HomeOverviewResource.values()) {
            client.success(resource, resource == HomeOverviewResource.UNREAD_NOTIFICATIONS
                    ? notificationPayload(1) : payload(resource.fieldName()));
        }
        HomeOverviewService service = new HomeOverviewService(client, properties());

        service.compose("Bearer user-a").block();
        service.compose("Bearer user-b").block();

        assertThat(client.count(HomeOverviewResource.THREADS)).isEqualTo(1);
        assertThat(client.count(HomeOverviewResource.SECTIONS)).isEqualTo(1);
        assertThat(client.count(HomeOverviewResource.PROFILE)).isEqualTo(2);
        assertThat(client.count(HomeOverviewResource.FOLLOWING)).isEqualTo(2);
        assertThat(client.count(HomeOverviewResource.UNREAD_NOTIFICATIONS)).isEqualTo(2);
        assertThat(client.authorizationValues(HomeOverviewResource.PROFILE))
                .containsExactlyInAnyOrder("Bearer user-a", "Bearer user-b");
    }

    private static HomeBffProperties properties() {
        HomeBffProperties properties = new HomeBffProperties();
        properties.setPublicCacheTtl(Duration.ofSeconds(5));
        return properties;
    }

    private static JsonNode payload(String value) {
        return JsonNodeFactory.instance.objectNode().put("value", value);
    }

    private static JsonNode notificationPayload(long total) {
        return JsonNodeFactory.instance.objectNode().put("total", total);
    }

    private record Call(HomeOverviewResource resource, String authorization) {
    }

    private static final class RecordingClient implements HomeOverviewClient {
        private final Map<HomeOverviewResource, HomeOverviewUpstreamResponse> responses =
                new EnumMap<>(HomeOverviewResource.class);
        private final List<Call> calls = new ArrayList<>();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();
        private Duration delay = Duration.ZERO;

        @Override
        public Mono<HomeOverviewUpstreamResponse> fetch(HomeOverviewResource resource, String authorization) {
            calls.add(new Call(resource, authorization));
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            Mono<HomeOverviewUpstreamResponse> response = Mono.justOrEmpty(responses.get(resource))
                    .switchIfEmpty(Mono.just(HomeOverviewUpstreamResponse.failure()));
            if (!delay.isZero()) {
                response = response.delaySubscription(delay);
            }
            return response.doFinally(signal -> active.decrementAndGet());
        }

        void success(HomeOverviewResource resource, JsonNode body) {
            response(resource, new HomeOverviewUpstreamResponse(200, body));
        }

        void response(HomeOverviewResource resource, HomeOverviewUpstreamResponse response) {
            responses.put(resource, response);
        }

        List<Call> calls() {
            return calls;
        }

        int count(HomeOverviewResource resource) {
            return (int) calls.stream().filter(call -> call.resource() == resource).count();
        }

        List<String> authorizationValues(HomeOverviewResource resource) {
            return calls.stream().filter(call -> call.resource() == resource)
                    .map(Call::authorization).toList();
        }

        int maximumActive() {
            return maximumActive.get();
        }
    }
}
