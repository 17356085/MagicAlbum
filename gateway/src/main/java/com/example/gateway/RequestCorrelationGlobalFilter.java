package com.example.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
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
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationGlobalFilter.class);
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
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route == null ? "unresolved" : route.getId();
        return chain.filter(exchange.mutate().request(request).build())
                .doOnSuccess(ignored -> log.info("Routed request requestId={} method={} route={} status={}",
                        requestId, request.getMethod(), routeId, exchange.getResponse().getStatusCode()))
                .doOnError(error -> log.warn("Route failed requestId={} method={} route={} errorType={}",
                        requestId, request.getMethod(), routeId, error.getClass().getSimpleName()));
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
