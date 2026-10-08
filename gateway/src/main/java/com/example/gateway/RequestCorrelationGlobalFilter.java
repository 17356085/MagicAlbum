package com.example.gateway;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public final class RequestCorrelationGlobalFilter implements GlobalFilter, Ordered {
    public static final String HEADER = "X-Request-Id";
    private static final int MAX_LENGTH = 128;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = normalize(exchange.getRequest().getHeaders().getFirst(HEADER));
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .header(HEADER, requestId)
                .build();
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String normalize(String candidate) {
        if (candidate == null || candidate.isBlank()
                || candidate.length() > MAX_LENGTH
                || !candidate.matches("[A-Za-z0-9._:-]+")) {
            return UUID.randomUUID().toString();
        }
        return candidate;
    }
}
