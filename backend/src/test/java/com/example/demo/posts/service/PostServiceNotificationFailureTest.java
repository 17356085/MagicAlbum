package com.example.demo.posts.service;

import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.dto.PostQueryView;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.service.UserSettingsService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceNotificationFailureTest {

    @Test
    void saved_comment_is_returned_when_notification_creation_fails() {
        PostRepository posts = mock(PostRepository.class);
        ThreadRepository threads = mock(ThreadRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.shouldDeliver(2L, "reply")).thenReturn(true);
        doThrow(new IllegalStateException("downstream unavailable"))
                .when(notificationService)
                .create(anyLong(), any(), any(), any(), any(), any(), any());
        NotificationClient notificationClient = new NotificationClient(notificationService, settings, true);

        Thread thread = new Thread();
        thread.setId(7L);
        thread.setAuthorId(2L);
        thread.setTitle("Thread");
        thread.setStatus("NORMAL");
        when(threads.findByIdForInteraction(7L)).thenReturn(Optional.of(thread));
        when(users.existsById(1L)).thenReturn(true);

        Post saved = new Post();
        saved.setId(10L);
        saved.setThreadId(7L);
        saved.setAuthorId(1L);
        saved.setContentMd("Hello");
        when(posts.save(any(Post.class))).thenReturn(saved);

        PostQueryView view = mock(PostQueryView.class);
        when(view.getId()).thenReturn(10L);
        when(view.getThreadId()).thenReturn(7L);
        when(view.getContent()).thenReturn("Hello");
        when(posts.findViewById(10L)).thenReturn(Optional.of(view));

        PostService service = new PostService(
                posts, null, threads, users, null, null, notificationClient);
        CreatePostRequest request = new CreatePostRequest();
        request.setContentMd("Hello");

        assertThat(service.create(1L, 7L, request)).isNotNull();
        verify(posts).save(any(Post.class));
        verify(notificationService).create(anyLong(), any(), any(), any(), any(), any(), any());
    }
}
