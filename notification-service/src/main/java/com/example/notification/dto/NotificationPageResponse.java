package com.example.notification.dto;

import java.util.List;

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
