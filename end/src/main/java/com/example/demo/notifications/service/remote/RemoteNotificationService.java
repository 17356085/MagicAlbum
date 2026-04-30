package com.example.demo.notifications.service.remote;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.notifications.service.mock.InMemoryNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.notifications.client.mode", havingValue = "remote")
public class RemoteNotificationService implements NotificationService {
    private static final Logger log = LoggerFactory.getLogger(RemoteNotificationService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> PAGE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final boolean enabled;
    private final InMemoryNotificationService fallback;

    public RemoteNotificationService(
            @Value("${app.notifications.client.base-url:http://localhost:8081}") String baseUrl,
            @Value("${app.notifications.client.enabled:true}") boolean enabled,
            InMemoryNotificationService fallback
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.fallback = fallback;
    }

    @Override
    public Map<String, Object> list(Long userId, String type, Boolean unread, int page, int size) {
        if (!enabled) {
            return fallback.list(userId, type, unread, page, size);
        }
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/notifications")
                            .queryParamIfPresent("type", optionalText(type))
                            .queryParamIfPresent("unread", java.util.Optional.ofNullable(unread))
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .header("X-User-Id", String.valueOf(userId))
                    .retrieve()
                    .body(PAGE_TYPE);
        } catch (Exception e) {
            log.warn("Failed to list notifications for user {}: {}", userId, e.getMessage());
            return fallback.list(userId, type, unread, page, size);
        }
    }

    @Override
    public NotificationDto markRead(Long userId, Long id) {
        if (!enabled) {
            return fallback.markRead(userId, id);
        }
        try {
            return restClient.patch()
                    .uri("/api/v1/notifications/{id}/read", id)
                    .header("X-User-Id", String.valueOf(userId))
                    .retrieve()
                    .body(NotificationDto.class);
        } catch (Exception e) {
            log.warn("Failed to mark notification {} read for user {}: {}", id, userId, e.getMessage());
            return fallback.markRead(userId, id);
        }
    }

    @Override
    public NotificationDto create(Long userId, String type, String title, String content,
                                  Long threadId, Long targetId, String targetType) {
        if (!enabled) {
            return fallback.create(userId, type, title, content, threadId, targetId, targetType);
        }
        try {
            return restClient.post()
                    .uri("/api/v1/notifications/internal")
                    .body(new CreateNotificationRequest(userId, type, title, content, threadId, targetId, targetType))
                    .retrieve()
                    .body(NotificationDto.class);
        } catch (Exception e) {
            log.warn("Failed to create {} notification for user {}: {}", type, userId, e.getMessage());
            return fallback.create(userId, type, title, content, threadId, targetId, targetType);
        }
    }

    private java.util.Optional<String> optionalText(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(value.trim());
    }

    private record CreateNotificationRequest(Long userId, String type, String title, String content,
                                             Long threadId, Long targetId, String targetType) {
    }
}
