package com.example.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import tools.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.ALWAYS)
record HomeOverviewResponse(
        JsonNode threads,
        JsonNode sections,
        JsonNode profile,
        JsonNode following,
        Long unreadNotifications,
        List<String> unavailable
) {
    HomeOverviewResponse {
        unavailable = List.copyOf(unavailable);
    }
}
