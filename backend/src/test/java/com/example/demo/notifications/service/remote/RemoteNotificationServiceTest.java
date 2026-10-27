package com.example.demo.notifications.service.remote;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.dto.NotificationPageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RemoteNotificationServiceTest {

    private static final String BASE_URL = "http://notification.test";
    private static final String USER_TOKEN = "Bearer browser-token";
    private static final String INTERNAL_TOKEN = "internal-service-token";

    @Test
    void list_forwards_browser_authorization_and_preserves_page_fields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteNotificationService service = new RemoteNotificationService(builder, BASE_URL, INTERNAL_TOKEN);

        server.expect(requestTo(BASE_URL + "/api/v1/notifications?type=reply&unread=true&page=2&size=10"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
                .andRespond(withSuccess(
                        "{\"items\":[],\"page\":2,\"size\":10,\"total\":0,\"source\":\"notification-service\"}",
                        MediaType.APPLICATION_JSON));

        NotificationPageResponse response = service.list(42L, USER_TOKEN, "reply", true, 2, 10);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.total()).isZero();
        assertThat(response.source()).isEqualTo("notification-service");
        server.verify();
    }

    @Test
    void mark_read_forwards_browser_authorization() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteNotificationService service = new RemoteNotificationService(builder, BASE_URL, INTERNAL_TOKEN);

        server.expect(requestTo(BASE_URL + "/api/v1/notifications/7/read"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header(HttpHeaders.AUTHORIZATION, USER_TOKEN))
                .andRespond(withSuccess(
                        "{\"id\":7,\"type\":\"reply\",\"title\":\"Title\",\"content\":\"Content\","
                                + "\"read\":true,\"createdAt\":\"2026-10-14T10:00:00Z\"}",
                        MediaType.APPLICATION_JSON));

        NotificationDto response = service.markRead(42L, USER_TOKEN, 7L);

        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.isRead()).isTrue();
        server.verify();
    }

    @Test
    void create_uses_only_internal_service_credential() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteNotificationService service = new RemoteNotificationService(builder, BASE_URL, INTERNAL_TOKEN);

        server.expect(requestTo(BASE_URL + "/api/v1/notifications/internal"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(request -> assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull())
                .andRespond(withSuccess(
                        "{\"id\":8,\"type\":\"reply\",\"title\":\"Title\",\"content\":\"Content\","
                                + "\"threadId\":9,\"targetId\":8,\"targetType\":\"post\","
                                + "\"read\":false,\"createdAt\":\"2026-10-14T10:00:00Z\"}",
                        MediaType.APPLICATION_JSON));

        NotificationDto response = service.create(42L, "reply", "Title", "Content", 9L, 8L, "post");

        assertThat(response.getId()).isEqualTo(8L);
        server.verify();
    }

    @Test
    void create_carries_event_id_and_maps_idempotency_conflict_without_downstream_body() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteNotificationService service = new RemoteNotificationService(builder, BASE_URL, INTERNAL_TOKEN);
        UUID eventId = UUID.randomUUID();

        server.expect(requestTo(BASE_URL + "/api/v1/notifications/internal"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains(eventId.toString());
                })
                .andRespond(withStatus(HttpStatus.CONFLICT).body("downstream-secret"));

        assertThatThrownBy(() -> service.create(42L, "reply", "Title", "Content", 9L, 8L, "post", eventId))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).doesNotContain("downstream-secret");
                });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void known_downstream_client_statuses_are_exposed_as_controlled_statuses(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteNotificationService service = new RemoteNotificationService(builder, BASE_URL, INTERNAL_TOKEN);

        server.expect(requestTo(BASE_URL + "/api/v1/notifications?page=1&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("downstream-secret"));

        assertThatThrownBy(() -> service.list(42L, USER_TOKEN, null, null, 1, 20))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(status);
                    assertThat(exception.getReason()).doesNotContain("downstream-secret");
                });
        server.verify();
    }

    @Test
    void downstream_server_failure_becomes_service_unavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteNotificationService service = new RemoteNotificationService(builder, BASE_URL, INTERNAL_TOKEN);

        server.expect(requestTo(BASE_URL + "/api/v1/notifications?page=1&size=20"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("downstream-secret"));

        assertThatThrownBy(() -> service.list(42L, USER_TOKEN, null, null, 1, 20))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getReason()).doesNotContain("downstream-secret");
                });
        server.verify();
    }
}
