package com.example.demo.notifications.service.remote;

import com.example.demo.notifications.client.InternalServiceCredential;
import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.dto.NotificationPageResponse;
import com.example.demo.notifications.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.notifications.client.mode", havingValue = "remote", matchIfMissing = true)
public class RemoteNotificationService implements NotificationService {
    private static final Logger log = LoggerFactory.getLogger(RemoteNotificationService.class);
    private static final ParameterizedTypeReference<NotificationPageResponse> PAGE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final String INTERNAL_CREDENTIAL_HEADER = "X-Internal-Service-Token";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RestClient restClient;
    private final String internalServiceToken;

    @Autowired
    public RemoteNotificationService(
            @Value("${app.notifications.client.base-url:http://localhost:8081}") String baseUrl,
            @Value("${app.notifications.client.internal-service-token:}") String internalServiceToken,
            @Value("${app.notifications.client.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${app.notifications.client.read-timeout-ms:5000}") long readTimeoutMs
    ) {
        this(timedBuilder(connectTimeoutMs, readTimeoutMs), baseUrl,
                InternalServiceCredential.requireStrongConfiguration(internalServiceToken));
    }

    public RemoteNotificationService(String baseUrl, String internalServiceToken) {
        this(baseUrl, internalServiceToken, 2000, 5000);
    }

    private static RestClient.Builder timedBuilder(long connectTimeoutMs, long readTimeoutMs) {
        if (connectTimeoutMs < 1 || connectTimeoutMs > 10000 || readTimeoutMs < 1 || readTimeoutMs > 10000) {
            throw new IllegalArgumentException("Notification timeouts must be between 1 and 10000 ms");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(factory);
    }

    RemoteNotificationService(RestClient.Builder restClientBuilder, String baseUrl, String internalServiceToken) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.internalServiceToken = internalServiceToken == null ? "" : internalServiceToken.trim();
    }

    @Override
    public NotificationPageResponse list(Long userId, String authorization, String type, Boolean unread, int page, int size) {
        try {
            NotificationPageResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/notifications")
                            .queryParamIfPresent("type", optionalText(type))
                            .queryParamIfPresent("unread", java.util.Optional.ofNullable(unread))
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .headers(this::addRequestId)
                    .retrieve()
                    .body(PAGE_TYPE);
            if (response == null) {
                throw new IllegalStateException("empty notification response");
            }
            return response;
        } catch (RestClientResponseException e) {
            throw mapDownstreamFailure(e);
        } catch (Exception e) {
            log.warn("notification_list_failed error={}", e.getClass().getSimpleName());
            throw serviceUnavailable();
        }
    }

    @Override
    public NotificationDto markRead(Long userId, String authorization, Long id) {
        try {
            return restClient.patch()
                    .uri("/api/v1/notifications/{id}/read", id)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .headers(this::addRequestId)
                    .retrieve()
                    .body(NotificationDto.class);
        } catch (RestClientResponseException e) {
            throw mapDownstreamFailure(e);
        } catch (Exception e) {
            log.warn("notification_mark_read_failed error={}", e.getClass().getSimpleName());
            throw serviceUnavailable();
        }
    }

    @Override
    public NotificationDto create(Long userId, String type, String title, String content,
                                  Long threadId, Long targetId, String targetType) {
        return create(userId, type, title, content, threadId, targetId, targetType, null);
    }

    @Override
    public NotificationDto create(Long userId, String type, String title, String content,
                                  Long threadId, Long targetId, String targetType, UUID eventId) {
        try {
            NotificationDto response = restClient.post()
                    .uri("/api/v1/notifications/internal")
                    .header(INTERNAL_CREDENTIAL_HEADER, internalServiceToken)
                    .headers(this::addRequestId)
                    .body(new CreateNotificationRequest(userId, type, title, content, threadId, targetId, targetType, eventId))
                    .retrieve()
                    .body(NotificationDto.class);
            if (response == null) {
                throw new IllegalStateException("empty notification response");
            }
            return response;
        } catch (RestClientResponseException e) {
            throw mapDownstreamFailure(e);
        } catch (Exception e) {
            log.warn("notification_create_failed type={} error={}", type, e.getClass().getSimpleName());
            throw serviceUnavailable();
        }
    }

    private ResponseStatusException mapDownstreamFailure(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return switch (status) {
            case 400 -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知请求参数不合法");
            case 401 -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "通知服务认证失败");
            case 403 -> new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问通知服务");
            case 404 -> new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
            case 408 -> new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "通知服务请求超时");
            case 429 -> new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "通知服务暂时繁忙");
            case 409 -> new ResponseStatusException(HttpStatus.CONFLICT, "通知事件幂等键冲突");
            default -> serviceUnavailable();
        };
    }

    private ResponseStatusException serviceUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "通知服务暂不可用");
    }

    private void addRequestId(HttpHeaders headers) {
        String requestId = MDC.get("requestId");
        if (requestId != null && !requestId.isBlank() && requestId.length() <= 128
                && requestId.matches("[A-Za-z0-9._:-]+")) {
            headers.set(REQUEST_ID_HEADER, requestId);
        }
    }

    private java.util.Optional<String> optionalText(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(value.trim());
    }

    private record CreateNotificationRequest(Long userId, String type, String title, String content,
                                             Long threadId, Long targetId, String targetType, UUID eventId) {
    }
}
