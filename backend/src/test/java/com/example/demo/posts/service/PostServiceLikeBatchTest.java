package com.example.demo.posts.service;

import com.example.demo.posts.dto.PostLikeCountView;
import com.example.demo.posts.dto.PostQueryView;
import com.example.demo.posts.repo.PostLikeRepository;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceLikeBatchTest {

    @Test
    void countFailureDoesNotEraseViewerBatchResult() {
        PostRepository posts = mock(PostRepository.class);
        PostLikeRepository likes = mock(PostLikeRepository.class);
        ThreadRepository threads = mock(ThreadRepository.class);
        UserRepository users = mock(UserRepository.class);
        PostQueryView view = view(1L);
        when(threads.findById(7L)).thenReturn(Optional.of(normalThread()));
        when(posts.findByThreadAscView(7L, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));
        when(likes.findLikeCountsByPostIds(List.of(1L)))
                .thenThrow(new DataAccessResourceFailureException("counts unavailable"));
        when(likes.findPostIdsLikedByUser(99L, List.of(1L))).thenReturn(List.of(1L));

        PostService service = service(posts, likes, threads, users);

        var result = service.listByThread(7L, 99L, "time", 1, 20).getContent().get(0);

        assertThat(result.getLikeCount()).isZero();
        assertThat(result.isLiked()).isTrue();
        verify(likes).findLikeCountsByPostIds(List.of(1L));
        verify(likes).findPostIdsLikedByUser(99L, List.of(1L));
    }

    @Test
    void viewerFailureDoesNotEraseLikeCountBatchResult() {
        PostRepository posts = mock(PostRepository.class);
        PostLikeRepository likes = mock(PostLikeRepository.class);
        ThreadRepository threads = mock(ThreadRepository.class);
        UserRepository users = mock(UserRepository.class);
        PostQueryView view = view(1L);
        PostLikeCountView count = mock(PostLikeCountView.class);
        when(threads.findById(7L)).thenReturn(Optional.of(normalThread()));
        when(posts.findByThreadAscView(7L, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));
        when(count.getPostId()).thenReturn(1L);
        when(count.getLikeCount()).thenReturn(4L);
        when(likes.findLikeCountsByPostIds(List.of(1L))).thenReturn(List.of(count));
        when(likes.findPostIdsLikedByUser(99L, List.of(1L)))
                .thenThrow(new DataAccessResourceFailureException("viewer state unavailable"));

        PostService service = service(posts, likes, threads, users);

        var result = service.listByThread(7L, 99L, "time", 1, 20).getContent().get(0);

        assertThat(result.getLikeCount()).isEqualTo(4L);
        assertThat(result.isLiked()).isFalse();
        verify(likes).findLikeCountsByPostIds(List.of(1L));
        verify(likes).findPostIdsLikedByUser(99L, List.of(1L));
    }

    @Test
    void emptyPageDoesNotQueryLikeRepository() {
        PostRepository posts = mock(PostRepository.class);
        PostLikeRepository likes = mock(PostLikeRepository.class);
        ThreadRepository threads = mock(ThreadRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(threads.findById(7L)).thenReturn(Optional.of(normalThread()));
        when(posts.findByThreadAscView(7L, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PostService service = service(posts, likes, threads, users);

        assertThat(service.listByThread(7L, 99L, "time", 1, 20).getContent()).isEmpty();

        verify(likes, never()).findLikeCountsByPostIds(any());
        verify(likes, never()).findPostIdsLikedByUser(anyLong(), any());
    }

    @Test
    void nullLikeRepositoryKeepsZeroCountAndFalseViewerState() {
        PostRepository posts = mock(PostRepository.class);
        ThreadRepository threads = mock(ThreadRepository.class);
        UserRepository users = mock(UserRepository.class);
        PostQueryView view = view(1L);
        when(threads.findById(7L)).thenReturn(Optional.of(normalThread()));
        when(posts.findByThreadAscView(7L, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        PostService service = new PostService(posts, threads, users);

        var result = service.listByThread(7L, 99L, "time", 1, 20).getContent().get(0);

        assertThat(result.getLikeCount()).isZero();
        assertThat(result.isLiked()).isFalse();
    }

    private static PostService service(PostRepository posts, PostLikeRepository likes,
                                       ThreadRepository threads, UserRepository users) {
        return new PostService(posts, likes, threads, users,
                (org.springframework.data.redis.core.StringRedisTemplate) null,
                (com.fasterxml.jackson.databind.ObjectMapper) null,
                (com.example.demo.notifications.client.NotificationClient) null);
    }

    private static PostQueryView view(Long id) {
        PostQueryView view = mock(PostQueryView.class);
        when(view.getId()).thenReturn(id);
        when(view.getThreadId()).thenReturn(7L);
        when(view.getContent()).thenReturn("comment");
        return view;
    }

    private static com.example.demo.threads.entity.Thread normalThread() {
        com.example.demo.threads.entity.Thread thread = new com.example.demo.threads.entity.Thread();
        thread.setId(7L);
        thread.setStatus("NORMAL");
        return thread;
    }
}
