package com.example.gateway;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;

final class HomeOverviewHttpClient implements HomeOverviewClient {
    private final WebClient coreClient;
    private final WebClient notificationClient;
    private final Duration callTimeout;

    HomeOverviewHttpClient(WebClient.Builder builder, HomeBffProperties properties) {
        this(builder, builder, properties);
    }

    HomeOverviewHttpClient(
            WebClient.Builder directBuilder,
            WebClient.Builder loadBalancedBuilder,
            HomeBffProperties properties
    ) {
        this.callTimeout = requirePositive(properties.getCallTimeout(), "home.bff.call-timeout");
        this.coreClient = buildClient(
                builderFor(directBuilder, loadBalancedBuilder, properties.getCoreBaseUrl(),
                        "home.bff.core-base-url"),
                properties.getCoreBaseUrl(), "home.bff.core-base-url");
        this.notificationClient = buildClient(
                builderFor(directBuilder, loadBalancedBuilder, properties.getNotificationBaseUrl(),
                        "home.bff.notification-base-url"),
                properties.getNotificationBaseUrl(), "home.bff.notification-base-url");
    }

    @Override
    public Mono<HomeOverviewUpstreamResponse> fetch(HomeOverviewResource resource, String authorization) {
        WebClient client = resource.notificationService() ? notificationClient : coreClient;
        WebClient.RequestHeadersSpec<?> request = client.get().uri(resource.path());
        if (resource.personalized()) {
            request = request.headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, authorization));
        }
        return request.exchangeToMono(response -> {
            int statusCode = response.statusCode().value();
            if (!response.statusCode().is2xxSuccessful()) {
                return response.releaseBody().thenReturn(new HomeOverviewUpstreamResponse(statusCode, null));
            }
            return response.bodyToMono(JsonNode.class)
                    .map(body -> new HomeOverviewUpstreamResponse(statusCode, body))
                    .switchIfEmpty(Mono.just(new HomeOverviewUpstreamResponse(statusCode, null)));
        }).timeout(callTimeout).onErrorResume(error -> Mono.just(HomeOverviewUpstreamResponse.failure()));
    }

    private static WebClient buildClient(WebClient.Builder source, URI baseUri, String propertyName) {
        Objects.requireNonNull(baseUri, propertyName + " must be configured when home.bff.enabled=true");
        requireTrustedOrigin(baseUri, propertyName);
        HttpClient httpClient = HttpClient.create().followRedirect(false);
        return source.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(baseUri.toString())
                .build();
    }

    private static WebClient.Builder builderFor(
            WebClient.Builder directBuilder,
            WebClient.Builder loadBalancedBuilder,
            URI baseUri,
            String propertyName
    ) {
        Objects.requireNonNull(baseUri, propertyName + " must be configured when home.bff.enabled=true");
        return "lb".equalsIgnoreCase(baseUri.getScheme()) ? loadBalancedBuilder : directBuilder;
    }

    private static void requireTrustedOrigin(URI baseUri, String propertyName) {
        String scheme = baseUri.getScheme();
        if (!baseUri.isAbsolute() || scheme == null
                || !(scheme.toLowerCase(Locale.ROOT).equals("http")
                || scheme.toLowerCase(Locale.ROOT).equals("https")
                || scheme.toLowerCase(Locale.ROOT).equals("lb"))
                || baseUri.getHost() == null || baseUri.getHost().isBlank()
                || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null
                || !(baseUri.getPath().isEmpty() || baseUri.getPath().equals("/"))) {
            throw new IllegalArgumentException(propertyName + " must be a trusted absolute origin");
        }
    }

    private static Duration requirePositive(Duration value, String propertyName) {
        Objects.requireNonNull(value, propertyName + " must be configured");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return value;
    }
}
