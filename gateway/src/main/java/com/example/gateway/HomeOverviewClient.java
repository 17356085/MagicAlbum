package com.example.gateway;

import reactor.core.publisher.Mono;

interface HomeOverviewClient {
    Mono<HomeOverviewUpstreamResponse> fetch(HomeOverviewResource resource, String authorization);
}
