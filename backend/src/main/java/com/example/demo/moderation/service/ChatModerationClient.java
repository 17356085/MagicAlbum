package com.example.demo.moderation.service;

import com.example.demo.notifications.client.InternalServiceCredential;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.chat.owner", havingValue = "remote")
class ChatModerationClient {
    private static final String BASE_PATH = "/internal/v1/chat/";
    private final RestClient client;

    ChatModerationClient(@Value("${app.chat.governance.url:http://chat-service:8083}") String url,
                         @Value("${app.chat.governance.token:}") String token,
                         @Value("${app.chat.internal-service-token:}") String contactToken,
                         @Value("${app.notifications.client.internal-service-token:}") String notificationToken,
                         @Value("${app.media.internal-service-token:}") String mediaToken) {
        var endpoint = URI.create(url);
        String scheme = endpoint.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme)) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))) {
            throw new IllegalStateException("Chat governance URL must be an HTTP(S) origin");
        }
        InternalServiceCredential.requireStrongConfiguration(token);
        if (token.equals(contactToken) || token.equals(notificationToken) || token.equals(mediaToken)) {
            throw new IllegalStateException("Chat governance requires a separate credential");
        }
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(Duration.ofSeconds(2));
        this.client = RestClient.builder().requestFactory(requests).baseUrl(url)
                .defaultHeader("X-Internal-Service-Token", token).build();
    }

    void requireReportable(long reporterId, long messageId) {
        try {
            var response = request("report-access", new Access(Long.toString(messageId), Long.toString(reporterId)))
                    .retrieve().toBodilessEntity();
            if (response.getStatusCode() != HttpStatus.NO_CONTENT) throw unavailable();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无法访问该用户或会话");
            }
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    String evidence(long messageId) {
        try {
            var result = request("report-evidence", new Target(Long.toString(messageId)))
                    .retrieve().body(Evidence.class);
            if (result == null || result.body() == null) throw unavailable();
            return result.body();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return null;
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    Outcome apply(ChatReportDecisionStore.Decision decision) {
        var command = new Command(decision.decisionId(), Long.toString(decision.version()),
                Long.toString(decision.messageId()), Long.toString(decision.reportId()), decision.hidden());
        try {
            var result = request("moderation-decisions", command).retrieve().body(Receipt.class);
            if (result == null || !command.decisionId().equals(result.decisionId())
                    || !command.version().equals(result.version()) || !command.messageId().equals(result.messageId())
                    || !command.reportId().equals(result.reportId()) || !Boolean.valueOf(command.hidden()).equals(result.hidden())
                    || result.outcome() == null) throw unavailable();
            return result.outcome();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return Outcome.TARGET_MISSING;
            if (exception.getStatusCode() == HttpStatus.CONFLICT) return Outcome.CONFLICT;
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private RestClient.RequestBodySpec request(String path, Object body) {
        return client.post().uri(BASE_PATH + path).contentType(MediaType.APPLICATION_JSON).headers(headers -> {
            String requestId = MDC.get("requestId");
            if (requestId != null) headers.set("X-Request-Id", requestId);
        }).body(body);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "聊天举报暂不可用，请重试");
    }

    enum Outcome { APPLIED, STALE, TARGET_MISSING, CONFLICT }
    private record Access(String messageId, String reporterId) {}
    private record Target(String messageId) {}
    private record Evidence(String body) {}
    private record Command(UUID decisionId, String version, String messageId, String reportId, boolean hidden) {}
    private record Receipt(UUID decisionId, String version, String messageId, String reportId, Boolean hidden, Outcome outcome) {}
}
