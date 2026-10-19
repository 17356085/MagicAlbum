package com.example.demo.notifications.outbox;

import com.example.demo.notifications.service.NotificationService;
import com.example.demo.notifications.service.remote.RemoteNotificationService;
import com.example.demo.user.service.UserSettingsService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies the durable correlation id reaches a real loopback HTTP receiver. */
class NotificationOutboxRequestCorrelationTest {
    private HttpServer server;

    @AfterEach
    void cleanup() {
        MDC.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void enqueue_delivery_and_internal_http_keep_the_same_request_id() throws Exception {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.shouldDeliver(101L, "reply")).thenReturn(true);
        when(repository.save(any(NotificationOutbox.class))).thenAnswer(invocation -> {
            NotificationOutbox row = invocation.getArgument(0);
            row.setId(7L);
            return row;
        });
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
        NotificationOutboxService queue = new NotificationOutboxService(
                repository, settings, clock, Duration.ofSeconds(30), 1, 3,
                Duration.ofMillis(1), Duration.ofDays(1));

        MDC.put("requestId", "m1-loopback-request");
        NotificationOutbox row = queue.enqueue(
                UUID.randomUUID(), 101L, "reply", "Title", "Content", 7L, 8L, "post").orElseThrow();
        assertThat(row.getRequestId()).isEqualTo("m1-loopback-request");
        NotificationOutboxService.Delivery delivery = NotificationOutboxService.Delivery.from(row, 1);

        AtomicReference<String> receivedRequestId = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/notifications/internal", exchange -> {
            receivedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"id\":900,\"type\":\"reply\",\"title\":\"Title\","
                    + "\"content\":\"Content\",\"read\":false,"
                    + "\"createdAt\":\"2026-10-19T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        when(outbox.batchSize()).thenReturn(1);
        when(outbox.claimOne(anyString())).thenReturn(Optional.of(delivery), Optional.empty());
        NotificationService remote = new RemoteNotificationService(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "internal-service-token-123456789012345678901234567890");
        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outbox, remote, settings);
        MDC.put("requestId", "outer-context");

        dispatcher.dispatch();

        assertThat(receivedRequestId).hasValue("m1-loopback-request");
        assertThat(MDC.get("requestId")).isEqualTo("outer-context");
    }
}
