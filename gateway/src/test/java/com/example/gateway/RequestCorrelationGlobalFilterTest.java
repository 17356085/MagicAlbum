package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationGlobalFilterTest {
    @Test
    void preserves_safe_id_in_downstream_request_and_response() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/threads")
                .header(RequestCorrelationGlobalFilter.HEADER, "request-123:test"));
        AtomicReference<String> forwarded = new AtomicReference<>();

        new RequestCorrelationGlobalFilter().filter(exchange, next -> {
            forwarded.set(next.getRequest().getHeaders().getFirst(RequestCorrelationGlobalFilter.HEADER));
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isEqualTo("request-123:test");
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestCorrelationGlobalFilter.HEADER))
                .isEqualTo(forwarded.get());
    }

    @Test
    void replaces_invalid_id_and_uses_same_value_downstream_and_in_response() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/threads")
                .header(RequestCorrelationGlobalFilter.HEADER, "unsafe id"));
        AtomicReference<String> forwarded = new AtomicReference<>();

        new RequestCorrelationGlobalFilter().filter(exchange, next -> {
            forwarded.set(next.getRequest().getHeaders().getFirst(RequestCorrelationGlobalFilter.HEADER));
            return Mono.empty();
        }).block();

        assertThat(UUID.fromString(forwarded.get())).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestCorrelationGlobalFilter.HEADER))
                .isEqualTo(forwarded.get());
    }
}
