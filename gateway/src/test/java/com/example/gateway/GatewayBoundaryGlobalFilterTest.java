package com.example.gateway;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayBoundaryGlobalFilterTest {
    private final GatewayBoundaryGlobalFilter filter = new GatewayBoundaryGlobalFilter();

    @Test
    void runsAfterRequestCorrelation() {
        assertThat(filter.getOrder()).isGreaterThan(new RequestCorrelationGlobalFilter().getOrder());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/notifications/internal",
            "/api/v1/notifications/internal/events",
            "/api/v1/chat/realtime/session",
            "/api/v1/chat/realtime/session/refresh",
            "/internal",
            "/internal/",
            "/internal/media/v1/jobs/42"
    })
    void rejects_private_paths_without_invoking_the_gateway_chain(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(GatewayBoundaryGlobalFilter.INTERNAL_SERVICE_TOKEN_HEADER, "forged-token"));
        AtomicInteger chainCalls = new AtomicInteger();

        filter.filter(exchange, next -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(chainCalls).hasValue(0);
    }

    @Test
    void strips_internal_token_before_forwarding_a_public_request() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/notifications/events")
                .header(GatewayBoundaryGlobalFilter.INTERNAL_SERVICE_TOKEN_HEADER, "forged-token"));
        AtomicInteger chainCalls = new AtomicInteger();
        AtomicReference<String> forwardedToken = new AtomicReference<>();

        filter.filter(exchange, next -> {
            chainCalls.incrementAndGet();
            forwardedToken.set(next.getRequest().getHeaders()
                    .getFirst(GatewayBoundaryGlobalFilter.INTERNAL_SERVICE_TOKEN_HEADER));
            return Mono.empty();
        }).block();

        assertThat(chainCalls).hasValue(1);
        assertThat(forwardedToken).hasValue(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/notifications/internalized",
            "/api/v1/chat/realtime/sessionized",
            "/internalized"
    })
    void uses_path_segments_when_matching_private_prefixes(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        AtomicInteger chainCalls = new AtomicInteger();

        filter.filter(exchange, next -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(chainCalls).hasValue(1);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
