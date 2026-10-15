package com.example.demo.notifications.client;

import com.example.demo.notifications.service.NotificationService;
import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationClientTest {
    @Test
    void disabled_preference_prevents_notification_creation() {
        NotificationService notifications = mock(NotificationService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.shouldDeliver(42L, "reply")).thenReturn(false);

        new NotificationClient(notifications, settings, true).sendReply(42L, "reply", "content");

        verify(settings).shouldDeliver(42L, "reply");
        verifyNoInteractions(notifications);
    }

    @Test
    void enabled_preference_creates_notification() {
        NotificationService notifications = mock(NotificationService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.shouldDeliver(42L, "follow")).thenReturn(true);

        new NotificationClient(notifications, settings, true).sendFollow(42L, "follow", "content", 7L);

        verify(notifications).create(eq(42L), eq("follow"), eq("follow"), eq("content"), isNull(), eq(7L), eq("user"));
    }

    @Test
    void notification_failure_is_best_effort_and_does_not_escape_to_primary_write() {
        NotificationService notifications = mock(NotificationService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.shouldDeliver(42L, "reply")).thenReturn(true);
        doThrow(new IllegalStateException("downstream unavailable"))
                .when(notifications)
                .create(eq(42L), eq("reply"), eq("reply"), eq("content"), isNull(), isNull(), eq("post"));

        assertDoesNotThrow(() -> new NotificationClient(notifications, settings, true)
                .sendReply(42L, "reply", "content"));

        verify(notifications).create(eq(42L), eq("reply"), eq("reply"), eq("content"), isNull(), isNull(), eq("post"));
    }
}
