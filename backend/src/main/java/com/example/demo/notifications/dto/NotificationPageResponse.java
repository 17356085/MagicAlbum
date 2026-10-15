package com.example.demo.notifications.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationPageResponse(
        List<NotificationDto> items,
        int page,
        int size,
        long total,
        String source
) {
    public NotificationPageResponse {
        items = List.copyOf(items);
        if (page < 1 || size < 1 || total < 0) {
            throw new IllegalArgumentException("Invalid notification pagination metadata");
        }
    }
}
