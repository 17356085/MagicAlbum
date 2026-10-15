package com.example.demo.user.service;

import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.user.repo.UserFollowRepository;
import com.example.demo.user.repo.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserFollowServiceNotificationFailureTest {

    @Test
    void saved_follow_is_returned_when_notification_creation_fails() {
        UserFollowRepository follows = mock(UserFollowRepository.class);
        UserRepository users = mock(UserRepository.class);
        UserProfileService profiles = mock(UserProfileService.class);
        NotificationService notificationService = mock(NotificationService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.shouldDeliver(2L, "follow")).thenReturn(true);
        doThrow(new IllegalStateException("downstream unavailable"))
                .when(notificationService)
                .create(anyLong(), any(), any(), any(), any(), any(), any());
        NotificationClient notificationClient = new NotificationClient(notificationService, settings, true);
        ObjectProvider<NotificationClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(notificationClient);

        when(users.existsById(2L)).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.empty());
        when(follows.existsByFollowerIdAndFollowingId(1L, 2L)).thenReturn(false, true);
        when(follows.countByFollowingId(2L)).thenReturn(1L);
        when(follows.countByFollowerId(2L)).thenReturn(0L);

        UserFollowService service = new UserFollowService(follows, users, profiles, provider);

        assertThat(service.follow(1L, 2L).isFollowing()).isTrue();
        verify(follows).save(any());
        verify(notificationService).create(anyLong(), any(), any(), any(), any(), any(), any());
    }
}
