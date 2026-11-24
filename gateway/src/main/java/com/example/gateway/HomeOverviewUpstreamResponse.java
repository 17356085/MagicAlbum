package com.example.gateway;

import tools.jackson.databind.JsonNode;

record HomeOverviewUpstreamResponse(int statusCode, JsonNode body) {
    static HomeOverviewUpstreamResponse failure() {
        return new HomeOverviewUpstreamResponse(0, null);
    }

    boolean hasPayload() {
        return statusCode >= 200 && statusCode < 300 && body != null;
    }

    boolean isUnauthorized() {
        return statusCode == 401 || statusCode == 403;
    }
}
