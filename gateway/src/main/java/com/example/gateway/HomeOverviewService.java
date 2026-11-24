package com.example.gateway;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

final class HomeOverviewService {
    private final HomeOverviewClient client;
    private final Duration publicCacheTtl;
    private final AtomicReference<Mono<PublicSnapshot>> publicCache = new AtomicReference<>();

    HomeOverviewService(HomeOverviewClient client, HomeBffProperties properties) {
        this.client = client;
        this.publicCacheTtl = properties.getPublicCacheTtl();
        if (publicCacheTtl == null || publicCacheTtl.isNegative()) {
            throw new IllegalArgumentException("home.bff.public-cache-ttl must not be negative");
        }
    }

    Mono<CompositionResult> compose(String authorization) {
        boolean authenticated = authorization != null && !authorization.isBlank();
        Mono<HomeOverviewUpstreamResponse> profile = authenticated
                ? safeFetch(HomeOverviewResource.PROFILE, authorization)
                : Mono.just(HomeOverviewUpstreamResponse.failure());
        Mono<HomeOverviewUpstreamResponse> following = authenticated
                ? safeFetch(HomeOverviewResource.FOLLOWING, authorization)
                : Mono.just(HomeOverviewUpstreamResponse.failure());
        Mono<HomeOverviewUpstreamResponse> notifications = authenticated
                ? safeFetch(HomeOverviewResource.UNREAD_NOTIFICATIONS, authorization)
                : Mono.just(HomeOverviewUpstreamResponse.failure());

        return Mono.zip(publicSnapshot(), profile, following, notifications)
                .map(tuple -> compose(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), authenticated));
    }

    private Mono<PublicSnapshot> publicSnapshot() {
        Mono<PublicSnapshot> cached = publicCache.get();
        if (cached != null) {
            return cached;
        }
        Mono<PublicSnapshot> candidate = Mono.zip(
                        safeFetch(HomeOverviewResource.THREADS, null),
                        safeFetch(HomeOverviewResource.SECTIONS, null))
                .map(tuple -> new PublicSnapshot(tuple.getT1(), tuple.getT2()));
        if (!publicCacheTtl.isZero()) {
            candidate = candidate.cache(publicCacheTtl);
            if (publicCache.compareAndSet(null, candidate)) {
                return candidate;
            }
            return publicCache.get();
        }
        return candidate;
    }

    private Mono<HomeOverviewUpstreamResponse> safeFetch(HomeOverviewResource resource, String authorization) {
        return client.fetch(resource, authorization)
                .onErrorReturn(HomeOverviewUpstreamResponse.failure());
    }

    private CompositionResult compose(
            PublicSnapshot publicSnapshot,
            HomeOverviewUpstreamResponse profile,
            HomeOverviewUpstreamResponse following,
            HomeOverviewUpstreamResponse notifications,
            boolean authenticated
    ) {
        if (authenticated && profile.isUnauthorized()) {
            return new CompositionResult(HttpStatusCode.valueOf(profile.statusCode()), null);
        }

        Set<String> unavailable = new LinkedHashSet<>();
        JsonNode threads = payloadOrUnavailable(
                HomeOverviewResource.THREADS, publicSnapshot.threads(), unavailable);
        JsonNode sections = payloadOrUnavailable(
                HomeOverviewResource.SECTIONS, publicSnapshot.sections(), unavailable);
        JsonNode profileBody = authenticated
                ? payloadOrUnavailable(HomeOverviewResource.PROFILE, profile, unavailable)
                : null;
        JsonNode followingBody = authenticated
                ? payloadOrUnavailable(HomeOverviewResource.FOLLOWING, following, unavailable)
                : null;
        Long unreadNotifications = authenticated
                ? unreadTotalOrUnavailable(notifications, unavailable)
                : null;

        return new CompositionResult(HttpStatus.OK,
                new HomeOverviewResponse(
                        threads,
                        sections,
                        profileBody,
                        followingBody,
                        unreadNotifications,
                        List.copyOf(unavailable)));
    }

    private static JsonNode payloadOrUnavailable(
            HomeOverviewResource resource,
            HomeOverviewUpstreamResponse response,
            Set<String> unavailable
    ) {
        if (response.hasPayload()) {
            return response.body();
        }
        unavailable.add(resource.fieldName());
        return null;
    }

    private static Long unreadTotalOrUnavailable(
            HomeOverviewUpstreamResponse response,
            Set<String> unavailable
    ) {
        if (response.hasPayload()) {
            JsonNode total = response.body().get("total");
            if (total != null && total.isIntegralNumber() && total.longValue() >= 0) {
                return total.longValue();
            }
        }
        unavailable.add(HomeOverviewResource.UNREAD_NOTIFICATIONS.fieldName());
        return null;
    }

    private record PublicSnapshot(
            HomeOverviewUpstreamResponse threads,
            HomeOverviewUpstreamResponse sections
    ) {
    }

    record CompositionResult(HttpStatusCode status, HomeOverviewResponse body) {
    }
}
