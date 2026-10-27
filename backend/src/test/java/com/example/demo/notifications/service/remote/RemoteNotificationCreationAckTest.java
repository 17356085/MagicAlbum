package com.example.demo.notifications.service.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RemoteNotificationCreationAckTest {
    private static final String BASE = "http://offline-notification.test";
    private static final String VALID = "{\"id\":900,\"type\":\"reply\",\"title\":\"Title\",\"content\":\"Content\","
            + "\"threadId\":7,\"targetId\":8,\"targetType\":\"post\",\"read\":true,\"createdAt\":\"2026-10-14T10:00:00Z\"}";

    static Stream<Arguments> invalidReceipts() {
        return Stream.of(
                Arguments.of("zero identifier", VALID.replace("\"id\":900", "\"id\":0")),
                Arguments.of("negative identifier", VALID.replace("\"id\":900", "\"id\":-1")),
                Arguments.of("identifier only", "{\"id\":900}"),
                Arguments.of("different type", VALID.replace("\"reply\"", "\"system\"")),
                Arguments.of("different title", VALID.replace("\"Title\"", "\"Another title\"")),
                Arguments.of("different content", VALID.replace("\"Content\"", "\"Another body\"")),
                Arguments.of("different thread", VALID.replace("\"threadId\":7", "\"threadId\":99")),
                Arguments.of("different target", VALID.replace("\"targetId\":8", "\"targetId\":99")),
                Arguments.of("different target type", VALID.replace("\"post\"", "\"user\"")),
                Arguments.of("missing target metadata", "{\"id\":900,\"type\":\"reply\",\"title\":\"Title\",\"content\":\"Content\"}"),
                Arguments.of("missing title", VALID.replace("\"title\":\"Title\",", ""))
        );
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidReceipts")
    void invalidSuccessfulCreationBodyDoesNotConfirmDelivery(String name, String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var remote = new RemoteNotificationService(builder, BASE, "isolated-test-token");
        server.expect(requestTo(BASE + "/api/v1/notifications/internal"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(body));
        assertThatThrownBy(() -> remote.create(101L, "reply", "Title", "Content", 7L, 8L, "post", UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(failure.getReason()).isEqualTo("通知服务暂不可用");
                });
        server.verify();
    }

    @Test
    void anAlreadyReadReplayAndReceiverTextTrimmingRemainValid() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var remote = new RemoteNotificationService(builder, BASE, "isolated-test-token");
        server.expect(requestTo(BASE + "/api/v1/notifications/internal"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(VALID));
        var received = remote.create(101L, " reply ", " Title ", " Content ", 7L, 8L, "post", UUID.randomUUID());
        assertThat(received.getId()).isEqualTo(900L);
        assertThat(received.isRead()).isTrue();
        assertThat(received.getCreatedAt()).isEqualTo(java.time.Instant.parse("2026-10-14T10:00:00Z"));
        server.verify();
    }

    @Test
    void genericNotificationWithoutATargetIsStillConfirmed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var remote = new RemoteNotificationService(builder, BASE, "isolated-test-token");
        server.expect(requestTo(BASE + "/api/v1/notifications/internal"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":900,\"type\":\"system\",\"title\":\"Title\",\"content\":\"Content\",\"read\":true}"));
        assertThat(remote.create(101L, "system", "Title", "Content", null, null, null).getId()).isEqualTo(900L);
        server.verify();
    }

    static Stream<Arguments> mixedTargets() {
        return Stream.of(
                Arguments.of("follow user", "follow", null, 8L, "user"),
                Arguments.of("thread without comment", "system", 7L, null, "thread"),
                Arguments.of("nullable target type", "system", 7L, 8L, null)
        );
    }

    @ParameterizedTest(name = "accepts {0}")
    @MethodSource("mixedTargets")
    void mixedNullableTargetFieldsRetainTheReceiverContract(String name, String type, Long thread, Long target, String targetType) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var remote = new RemoteNotificationService(builder, BASE, "isolated-test-token");
        String body = "{\"id\":900,\"type\":\"" + type + "\",\"title\":\"Title\",\"content\":\"Content\",\"read\":true,"
                + "\"threadId\":" + thread + ",\"targetId\":" + target + ",\"targetType\":"
                + (targetType == null ? "null" : "\"" + targetType + "\"") + "}";
        server.expect(requestTo(BASE + "/api/v1/notifications/internal"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(body));
        var response = remote.create(101L, type, "Title", "Content", thread, target, targetType, UUID.randomUUID());
        assertThat(response.getThreadId()).isEqualTo(thread);
        assertThat(response.getTargetId()).isEqualTo(target);
        assertThat(response.getTargetType()).isEqualTo(targetType);
        server.verify();
    }
}
