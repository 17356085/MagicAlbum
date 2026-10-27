package com.example.demo.notifications.outbox;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxDispatcherTest {
    private NotificationOutboxService outboxService;
    private NotificationService notificationService;
    private UserSettingsService userSettingsService;
    private NotificationOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        MDC.clear();
        outboxService = org.mockito.Mockito.mock(NotificationOutboxService.class);
        notificationService = org.mockito.Mockito.mock(NotificationService.class);
        userSettingsService = org.mockito.Mockito.mock(UserSettingsService.class);
        when(outboxService.batchSize()).thenReturn(1);
        dispatcher = new NotificationOutboxDispatcher(outboxService, notificationService, userSettingsService);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void delivery_failure_is_recorded_for_retry_without_leaking_payload_or_stopping_poll() {
        NotificationOutboxService.Delivery delivery = delivery();
        when(outboxService.claimOne(anyString())).thenReturn(Optional.of(delivery), Optional.empty());
        when(userSettingsService.shouldDeliver(101L, "reply")).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "downstream detail must not persist"))
                .when(notificationService)
                .create(eq(101L), eq("reply"), eq("Title"), eq("Content"), eq(7L), eq(8L), eq("post"), eq(UUID.fromString(delivery.eventId())));

        dispatcher.dispatch();

        verify(outboxService).markFailedOrRetry(eq(delivery), anyString(), any(ResponseStatusException.class));
        verify(outboxService).cleanupTerminal();
    }

    @Test
    void preference_change_before_http_marks_event_suppressed() {
        NotificationOutboxService.Delivery delivery = delivery();
        when(outboxService.claimOne(anyString())).thenReturn(Optional.of(delivery), Optional.empty());
        when(userSettingsService.shouldDeliver(101L, "reply")).thenReturn(false);

        dispatcher.dispatch();

        verify(outboxService).markSuppressed(eq(delivery.id()), anyString(), eq("preference disabled before delivery"));
        verify(notificationService, never()).create(
                anyLong(), anyString(), anyString(), anyString(), any(), any(), anyString(), any(UUID.class));
    }

    @Test
    void every_retry_reuses_the_same_event_id_for_downstream_deduplication() {
        NotificationOutboxService.Delivery delivery = delivery();
        when(outboxService.claimOne(anyString())).thenReturn(Optional.of(delivery), Optional.empty());
        when(userSettingsService.shouldDeliver(101L, "reply")).thenReturn(true);
        when(notificationService.create(
                101L, "reply", "Title", "Content", 7L, 8L, "post", UUID.fromString(delivery.eventId())))
                .thenReturn(new NotificationDto(900L, "reply", "Title", "Content", false, Instant.now(), 7L, 8L, "post"));

        dispatcher.dispatch();

        verify(notificationService).create(
                101L, "reply", "Title", "Content", 7L, 8L, "post", UUID.fromString(delivery.eventId()));
        verify(outboxService).markDelivered(eq(delivery.id()), anyString());
        assertThat(UUID.fromString(delivery.eventId())).isNotNull();
    }

    @Test
    void empty_downstream_response_is_retried_instead_of_marked_delivered() {
        NotificationOutboxService.Delivery delivery = delivery();
        when(outboxService.claimOne(anyString())).thenReturn(Optional.of(delivery), Optional.empty());
        when(userSettingsService.shouldDeliver(101L, "reply")).thenReturn(true);
        when(notificationService.create(
                101L, "reply", "Title", "Content", 7L, 8L, "post", UUID.fromString(delivery.eventId())))
                .thenReturn(null);

        dispatcher.dispatch();

        verify(outboxService).markFailedOrRetry(eq(delivery), anyString(), any(IllegalStateException.class));
        verify(outboxService, never()).markDelivered(anyLong(), anyString());
    }

    @Test
    void delivery_request_id_is_set_for_each_event_and_restored_after_failure() {
        NotificationOutboxService.Delivery delivery = delivery("m1-delivery-request");
        List<String> observedRequestIds = new ArrayList<>();
        when(outboxService.claimOne(anyString())).thenReturn(Optional.of(delivery), Optional.empty());
        when(userSettingsService.shouldDeliver(101L, "reply")).thenReturn(true);
        doAnswer(invocation -> {
            observedRequestIds.add(MDC.get("requestId"));
            throw new IllegalStateException("downstream detail must not persist");
        }).when(notificationService).create(
                eq(101L), eq("reply"), eq("Title"), eq("Content"), eq(7L), eq(8L), eq("post"),
                eq(UUID.fromString(delivery.eventId())));
        MDC.put("requestId", "outer-request");

        dispatcher.dispatch();

        assertThat(observedRequestIds).containsExactly("m1-delivery-request");
        assertThat(MDC.get("requestId")).isEqualTo("outer-request");
    }

    @Test
    void separate_events_do_not_leak_request_ids_between_deliveries() {
        NotificationOutboxService.Delivery first = delivery("m1-first");
        NotificationOutboxService.Delivery second = delivery("m1-second");
        List<String> observedRequestIds = new ArrayList<>();
        when(outboxService.batchSize()).thenReturn(2);
        when(outboxService.claimOne(anyString())).thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        when(userSettingsService.shouldDeliver(101L, "reply")).thenReturn(true);
        when(notificationService.create(
                anyLong(), anyString(), anyString(), anyString(), any(), any(), anyString(), any(UUID.class)))
                .thenAnswer(invocation -> {
                    observedRequestIds.add(MDC.get("requestId"));
                    return new NotificationDto(900L, "reply", "Title", "Content", false, Instant.now(), 7L, 8L, "post");
                });

        dispatcher.dispatch();

        assertThat(observedRequestIds).containsExactly("m1-first", "m1-second");
        assertThat(MDC.get("requestId")).isNull();
    }

    private NotificationOutboxService.Delivery delivery() {
        return delivery(null);
    }

    private NotificationOutboxService.Delivery delivery(String requestId) {
        return new NotificationOutboxService.Delivery(
                1L,
                UUID.randomUUID().toString(),
                101L,
                "reply",
                "Title",
                "Content",
                7L,
                8L,
                "post",
                1,
                requestId
        );
    }

}
