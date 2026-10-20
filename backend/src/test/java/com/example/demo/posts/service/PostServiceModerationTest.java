package com.example.demo.posts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostLikeRepository;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceModerationTest {
    private final PostRepository posts = mock(PostRepository.class);
    private final ThreadRepository threads = mock(ThreadRepository.class);
    private final PostService service = new PostService(posts, threads, mock(UserRepository.class));

    @Test
    void hiddenThreadDoesNotExposeOrAcceptComments() {
        Thread hidden = thread("HIDDEN");
        when(threads.findById(7L)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> service.listByThread(7L, 1, 20))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void moderatorHideMakesPostInactive() {
        Post post = new Post();
        post.setId(12L);
        post.setThreadId(7L);
        post.setStatus("NORMAL");
        when(posts.findByIdForInteraction(12L)).thenReturn(Optional.of(post));
        when(posts.findThreadIdByPostId(12L)).thenReturn(Optional.of(7L));
        when(threads.findByIdForInteraction(7L)).thenReturn(Optional.of(thread("NORMAL")));

        service.hideForModeration(12L);

        assertThat(post.getStatus()).isEqualTo("HIDDEN");
        verify(posts).save(post);
    }

    @Test
    void authorDeleteSoftDeletesPostAndKeepsItForReportScope() {
        Post post = new Post();
        post.setId(12L);
        post.setThreadId(7L);
        post.setAuthorId(1L);
        post.setStatus("NORMAL");
        when(posts.findThreadIdByPostId(12L)).thenReturn(Optional.of(7L));
        when(posts.findByIdForInteraction(12L)).thenReturn(Optional.of(post));
        when(threads.findByIdForInteraction(7L)).thenReturn(Optional.of(thread("NORMAL")));

        service.delete(1L, 12L);

        assertThat(post.getStatus()).isEqualTo("DELETED");
        verify(posts).save(post);
        verify(posts, never()).deleteById(12L);
    }

    @Test
    void moderationCannotRestoreAuthorDeletedPost() {
        Post post = new Post();
        post.setId(12L);
        post.setThreadId(7L);
        post.setStatus("DELETED");
        when(posts.findThreadIdByPostId(12L)).thenReturn(Optional.of(7L));
        when(posts.findByIdForInteraction(12L)).thenReturn(Optional.of(post));
        when(threads.findByIdForInteraction(7L)).thenReturn(Optional.of(thread("NORMAL")));

        assertThatThrownBy(() -> service.restoreForModeration(12L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(posts, never()).save(any(Post.class));
    }

    @Test
    void likeLocksThreadBeforePostAndDoesNotUseGovernanceRestrictions() {
        PostLikeRepository likes = mock(PostLikeRepository.class);
        Post post = new Post();
        post.setId(12L);
        post.setThreadId(7L);
        post.setStatus("NORMAL");
        Thread thread = thread("NORMAL");
        when(posts.findThreadIdByPostId(12L)).thenReturn(Optional.of(7L));
        when(threads.findByIdForInteraction(7L)).thenReturn(Optional.of(thread));
        when(posts.findByIdForInteraction(12L)).thenReturn(Optional.of(post));
        when(likes.existsByPostIdAndUserId(12L, 2L)).thenReturn(false);
        when(likes.countByPostId(12L)).thenReturn(1L);

        PostService interactionService = new PostService(
                posts, likes, threads, mock(UserRepository.class),
                (StringRedisTemplate) null, (ObjectMapper) null, (NotificationClient) null);
        interactionService.like(2L, 12L);

        var order = inOrder(threads, posts);
        order.verify(threads).findByIdForInteraction(7L);
        order.verify(posts).findByIdForInteraction(12L);
        verify(likes).save(any());
    }

    @Test
    void createLocksThreadBeforeReplyParent() {
        UserRepository users = mock(UserRepository.class);
        Thread thread = thread("NORMAL");
        Post parent = new Post();
        parent.setId(13L);
        parent.setThreadId(7L);
        parent.setStatus("NORMAL");
        when(users.existsById(1L)).thenReturn(true);
        when(threads.findByIdForInteraction(7L)).thenReturn(Optional.of(thread));
        when(posts.findByIdForInteraction(13L)).thenReturn(Optional.of(parent));
        when(posts.save(any(Post.class))).thenReturn(null);

        PostService createService = new PostService(posts, threads, users);
        CreatePostRequest request = new CreatePostRequest();
        request.setContentMd("child");
        request.setReplyToPostId(13L);
        createService.create(1L, 7L, request);

        var order = inOrder(threads, posts);
        order.verify(threads).findByIdForInteraction(7L);
        order.verify(posts).findByIdForInteraction(13L);
    }

    @Test
    void createReturnsNotFoundAndDoesNotSaveWhenThreadLockIsMissing() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsById(1L)).thenReturn(true);
        when(threads.findByIdForInteraction(7L)).thenReturn(Optional.empty());

        CreatePostRequest request = new CreatePostRequest();
        request.setContentMd("child");

        assertThatThrownBy(() -> new PostService(posts, threads, users).create(1L, 7L, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(posts, never()).save(any(Post.class));
    }

    @Test
    void likeReturnsNotFoundAndDoesNotSaveWhenThreadLockIsMissing() {
        PostLikeRepository likes = mock(PostLikeRepository.class);
        when(posts.findThreadIdByPostId(12L)).thenReturn(Optional.of(7L));
        when(threads.findByIdForInteraction(7L)).thenReturn(Optional.empty());

        PostService interactionService = new PostService(
                posts, likes, threads, mock(UserRepository.class),
                (StringRedisTemplate) null, (ObjectMapper) null, (NotificationClient) null);

        assertThatThrownBy(() -> interactionService.like(2L, 12L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(posts, never()).findByIdForInteraction(12L);
        verify(likes, never()).save(any());
    }

    private static Thread thread(String status) {
        Thread thread = new Thread();
        thread.setId(7L);
        thread.setStatus(status);
        return thread;
    }
}
