package com.example.demo.notifications.outbox;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.notifications.service.rabbit.CommentCreatedRabbitTransport;
import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxCommentCreatedDispatchTest {
    @Test
    void opt_in_comment_intent_uses_rabbit_ack_before_marking_outbox_delivered() {
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        NotificationService http = mock(NotificationService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        CommentCreatedRabbitTransport rabbit = mock(CommentCreatedRabbitTransport.class);
        NotificationOutboxService.Delivery delivery = new NotificationOutboxService.Delivery(
                1L, UUID.randomUUID().toString(), 101L, "reply", "Title", "Content", 7L, 8L, "post", 1);
        NotificationDto dto = new NotificationDto(
                900L, "reply", "Title", "Content", false, Instant.now(), 7L, 8L, "post");
        when(outbox.batchSize()).thenReturn(1);
        when(outbox.claimOne(anyString())).thenReturn(Optional.of(delivery), Optional.empty());
        when(settings.shouldDeliver(101L, "reply")).thenReturn(true);
        when(rabbit.deliver(delivery)).thenReturn(dto);

        new NotificationOutboxDispatcher(outbox, http, settings, rabbit).dispatch();

        verify(rabbit).deliver(delivery);
        verify(http, never()).create(eq(101L), eq("reply"), eq("Title"), eq("Content"),
                eq(7L), eq(8L), eq("post"), eq(UUID.fromString(delivery.eventId())));
        verify(outbox).markDelivered(eq(1L), anyString());
    }

    @Test
    void timeout_retries_same_event_without_http_fallback() {
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        NotificationService http = mock(NotificationService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        CommentCreatedRabbitTransport rabbit = mock(CommentCreatedRabbitTransport.class);
        NotificationOutboxService.Delivery delivery = new NotificationOutboxService.Delivery(
                1L, UUID.randomUUID().toString(), 101L, "reply", "Title", "Content", 7L, 8L, "post", 1);
        when(outbox.batchSize()).thenReturn(1);
        when(outbox.claimOne(anyString())).thenReturn(Optional.of(delivery), Optional.of(delivery));
        when(settings.shouldDeliver(101L, "reply")).thenReturn(true);
        when(rabbit.deliver(delivery)).thenThrow(new com.example.demo.notifications.service.rabbit.CommentCreatedDeliveryException(
                com.example.demo.notifications.service.rabbit.CommentCreatedDeliveryException.Code.TIMEOUT));

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(outbox, http, settings, rabbit);
        dispatcher.dispatch();
        dispatcher.dispatch();

        verify(rabbit, times(2)).deliver(delivery);
        verify(outbox, times(2)).markFailedOrRetry(eq(delivery), anyString(), any(RuntimeException.class));
        verify(http, never()).create(eq(101L), eq("reply"), eq("Title"), eq("Content"),
                eq(7L), eq(8L), eq("post"), eq(UUID.fromString(delivery.eventId())));
    }
}
