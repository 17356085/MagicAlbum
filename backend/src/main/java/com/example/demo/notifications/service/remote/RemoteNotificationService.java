package com.example.demo.notifications.service.remote;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.notifications.client.mode", havingValue = "remote", matchIfMissing = true)
public class RemoteNotificationService implements NotificationService {
    private static final Logger log = LoggerFactory.getLogger(RemoteNotificationService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> PAGE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final String INTERNAL_CREDENTIAL_HEADER = "X-Internal-Service-Token";

    private final RestClient restClient;
    private final String internalServiceToken;

    public RemoteNotificationService(
            @Value("${app.notifications.client.base-url:http://localhost:8081}") String baseUrl,
            @Value("${app.notifications.client.internal-service-token:}") String internalServiceToken
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalServiceToken = internalServiceToken == null ? "" : internalServiceToken.trim();
    }

    @Override
    public Map<String, Object> list(Long userId, String type, Boolean unread, int page, int size) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/notifications")
                            .queryParamIfPresent("type", optionalText(type))
                            .queryParamIfPresent("unread", java.util.Optional.ofNullable(unread))
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .retrieve()
                    .body(PAGE_TYPE);
            if (response == null) {
                throw new IllegalStateException("empty notification response");
            }
            return response;
        } catch (Exception e) {
            log.warn("notification_list_failed error={}", e.getClass().getSimpleName());
            throw new IllegalStateException("通知服务暂不可用", e);
        }
    }

    @Override
    public NotificationDto markRead(Long userId, Long id) {
        try {
            return restClient.patch()
                    .uri("/api/v1/notifications/{id}/read", id)
                    .retrieve()
                    .body(NotificationDto.class);
        } catch (Exception e) {
            log.warn("notification_mark_read_failed error={}", e.getClass().getSimpleName());
            throw new IllegalStateException("通知服务暂不可用", e);
        }
    }

    @Override
    public NotificationDto create(Long userId, String type, String title, String content,
                                  Long threadId, Long targetId, String targetType) {
        try {
            NotificationDto response = restClient.post()
                    .uri("/api/v1/notifications/internal")
                    .header(INTERNAL_CREDENTIAL_HEADER, internalServiceToken)
                    .body(new CreateNotificationRequest(userId, type, title, content, threadId, targetId, targetType))
                    .retrieve()
                    .body(NotificationDto.class);
            if (response == null) {
                throw new IllegalStateException("empty notification response");
            }
            return response;
        } catch (Exception e) {
            log.warn("notification_create_failed type={} error={}", type, e.getClass().getSimpleName());
            throw new IllegalStateException("通知服务暂不可用", e);
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
