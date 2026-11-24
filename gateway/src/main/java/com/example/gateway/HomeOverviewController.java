package com.example.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/home")
@ConditionalOnProperty(prefix = "home.bff", name = "enabled", havingValue = "true")
final class HomeOverviewController {
    private final HomeOverviewService service;

    HomeOverviewController(HomeOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    Mono<ResponseEntity<HomeOverviewResponse>> overview(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            ServerHttpResponse httpResponse
    ) {
        boolean authenticated = authorization != null && !authorization.isBlank();
        if (authenticated) {
            httpResponse.getHeaders().set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        }
        return service.compose(authorization).map(result -> {
            ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
            if (authenticated) {
                response.header(HttpHeaders.CACHE_CONTROL, "private, no-store");
            }
            if (result.body() == null) {
                return response.<HomeOverviewResponse>build();
            }
            return response.body(result.body());
        });
    }
}
