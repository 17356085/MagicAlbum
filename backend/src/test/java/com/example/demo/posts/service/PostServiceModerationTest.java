package com.example.demo.posts.service;

import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
        when(threads.findById(7L)).thenReturn(Optional.of(thread("NORMAL")));

        service.hideForModeration(12L);

        assertThat(post.getStatus()).isEqualTo("HIDDEN");
        verify(posts).save(post);
    }

    private static Thread thread(String status) {
        Thread thread = new Thread();
        thread.setId(7L);
        thread.setStatus(status);
        return thread;
    }
}
