package com.example.gateway;

import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

@Component
public final class GatewayBoundaryGlobalFilter implements GlobalFilter, Ordered {
    public static final String INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private static final List<PathPattern> BLOCKED_PATHS = List.of(
            PathPatternParser.defaultInstance.parse("/api/v1/notifications/internal/**"),
            PathPatternParser.defaultInstance.parse("/api/v1/chat/realtime/session/**"),
            PathPatternParser.defaultInstance.parse("/internal/**"));

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        var path = request.getPath().pathWithinApplication();
        if (BLOCKED_PATHS.stream().anyMatch(pattern -> pattern.matches(path))) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        if (request.getHeaders().getFirst(INTERNAL_SERVICE_TOKEN_HEADER) == null) return chain.filter(exchange);
        return chain.filter(exchange.mutate().request(builder ->
                builder.headers(headers -> headers.remove(INTERNAL_SERVICE_TOKEN_HEADER))).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
