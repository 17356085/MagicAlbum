package com.example.demo.notifications.outbox;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
        outboxService = org.mockito.Mockito.mock(NotificationOutboxService.class);
        notificationService = org.mockito.Mockito.mock(NotificationService.class);
        userSettingsService = org.mockito.Mockito.mock(UserSettingsService.class);
        when(outboxService.batchSize()).thenReturn(1);
        dispatcher = new NotificationOutboxDispatcher(outboxService, notificationService, userSettingsService);
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
                .thenReturn(new NotificationDto(900L, "reply", "Title", "Content", false, Instant.now()));

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

    private NotificationOutboxService.Delivery delivery() {
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
                1
        );
    }

}
