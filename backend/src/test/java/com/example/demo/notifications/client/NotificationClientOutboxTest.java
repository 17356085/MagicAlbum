package com.example.demo.notifications.client;

import com.example.demo.notifications.outbox.NotificationOutboxService;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.user.service.UserSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationClientOutboxTest {
    @Test
    void remote_mode_writes_to_outbox_and_does_not_call_downstream_http_directly() {
        NotificationService notificationService = mock(NotificationService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<NotificationOutboxService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(outbox);

        NotificationClient client = new NotificationClient(
                notificationService, settings, provider, "remote", true);
        client.sendFollow(42L, "Follow", "Content", 7L);

        verify(outbox).enqueue(42L, "follow", "Follow", "Content", null, 7L, "user");
        org.mockito.Mockito.verifyNoInteractions(notificationService, settings);
    }

    @Test
    void outbox_database_failure_escapes_to_the_primary_transaction() {
        NotificationService notificationService = mock(NotificationService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<NotificationOutboxService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(outbox);
        doThrow(new IllegalStateException("queue unavailable"))
                .when(outbox)
                .enqueue(eq(42L), eq("reply"), eq("Reply"), eq("Content"), isNull(), isNull(), eq("post"));

        NotificationClient client = new NotificationClient(
                notificationService, settings, provider, "remote", true);

        assertThatThrownBy(() -> client.sendReply(42L, "Reply", "Content"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("queue unavailable");
    }
}
