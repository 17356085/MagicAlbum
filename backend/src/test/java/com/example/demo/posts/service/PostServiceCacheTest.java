package com.example.demo.posts.service;

import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.dto.PostDto;
import com.example.demo.posts.dto.PostQueryView;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceCacheTest {

    private static com.example.demo.threads.entity.Thread normalThread() {
        com.example.demo.threads.entity.Thread thread = new com.example.demo.threads.entity.Thread();
        thread.setId(7L);
        thread.setStatus("NORMAL");
        return thread;
    }

    @Test
    void listByThread_should_cache_result() {
        PostRepository postRepository = mock(PostRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(threadRepository.findById(7L)).thenReturn(Optional.of(normalThread()));

        PostQueryView view = mock(PostQueryView.class);
        when(view.getId()).thenReturn(1L);
        when(view.getThreadId()).thenReturn(7L);
        when(view.getThreadTitle()).thenReturn("Thread");
        when(view.getAuthorId()).thenReturn(11L);
        when(view.getAuthorUsername()).thenReturn("alice");
        when(view.getAuthorNickname()).thenReturn("Alice");
        when(view.getAuthorAvatarUrl()).thenReturn("/a.png");
        when(view.getContent()).thenReturn("Hello");
        when(postRepository.findByThreadAscView(anyLong(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        PostService service = new PostService(postRepository, threadRepository, userRepository, redisTemplate, null);
        var result = service.listByThread(7L, 1, 20);

        assertThat(result.getContent()).hasSize(1);
        verify(postRepository, times(1)).findByThreadAscView(anyLong(), any(PageRequest.class));
        verify(valueOperations, times(1)).set(anyString(), anyString(), any());
    }

    @Test
    void listByThread_should_return_cached_result() throws Exception {
        PostRepository postRepository = mock(PostRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(threadRepository.findById(7L)).thenReturn(Optional.of(normalThread()));

        PostDto dto = new PostDto();
        dto.setId(1L);
        dto.setThreadId(7L);
        dto.setContent("Cached");
        Instant updatedAt = Instant.parse("2026-10-18T00:00:00Z");
        dto.setUpdatedAt(updatedAt);
        Post authoritative = new Post();
        authoritative.setId(1L);
        authoritative.setThreadId(7L);
        authoritative.setStatus("NORMAL");
        authoritative.setUpdatedAt(updatedAt);
        when(postRepository.findAllById(List.of(1L))).thenReturn(List.of(authoritative));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(new Object() {
            public final List<PostDto> items = List.of(dto);
            public final long total = 1L;
        });
        when(valueOperations.get(anyString())).thenReturn(json);

        PostService service = new PostService(postRepository, threadRepository, userRepository, redisTemplate, mapper);
        var result = service.listByThread(7L, 1, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getContent()).isEqualTo("Cached");
        verify(postRepository, times(0)).findByThreadAscView(anyLong(), any(PageRequest.class));
    }

    @Test
    void authenticated_listByThread_should_bypass_anonymous_cache_and_resolve_liked() {
        PostRepository postRepository = mock(PostRepository.class);
        com.example.demo.posts.repo.PostLikeRepository postLikeRepository = mock(com.example.demo.posts.repo.PostLikeRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(threadRepository.findById(7L)).thenReturn(Optional.of(normalThread()));

        PostQueryView view = mock(PostQueryView.class);
        when(view.getId()).thenReturn(1L);
        when(view.getThreadId()).thenReturn(7L);
        when(view.getContent()).thenReturn("Current");
        when(postRepository.findByThreadAscView(7L, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));
        when(postLikeRepository.existsByPostIdAndUserId(1L, 99L)).thenReturn(true);

        PostService service = new PostService(postRepository, postLikeRepository, threadRepository,
                userRepository, redisTemplate, null, null);

        assertThat(service.listByThread(7L, 99L, "time", 1, 20).getContent().get(0).isLiked()).isTrue();
        verify(valueOperations, never()).get(anyString());
        verify(postLikeRepository).existsByPostIdAndUserId(1L, 99L);
    }

    @Test
    void anonymous_cache_should_fallback_when_authoritative_post_is_hidden() throws Exception {
        PostRepository postRepository = mock(PostRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(threadRepository.findById(7L)).thenReturn(Optional.of(normalThread()));

        Instant updatedAt = Instant.parse("2026-10-18T00:00:00Z");
        PostDto dto = new PostDto();
        dto.setId(1L);
        dto.setThreadId(7L);
        dto.setUpdatedAt(updatedAt);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        when(valueOperations.get(anyString())).thenReturn(mapper.writeValueAsString(new Object() {
            public final List<PostDto> items = List.of(dto);
            public final long total = 1L;
        }));
        Post hidden = new Post();
        hidden.setId(1L);
        hidden.setThreadId(7L);
        hidden.setStatus("HIDDEN");
        hidden.setUpdatedAt(updatedAt);
        when(postRepository.findAllById(List.of(1L))).thenReturn(List.of(hidden));
        when(postRepository.findByThreadAscView(7L, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PostService service = new PostService(postRepository, threadRepository, userRepository, redisTemplate, mapper);

        assertThat(service.listByThread(7L, 1, 20).getContent()).isEmpty();
        verify(postRepository).findByThreadAscView(7L, PageRequest.of(0, 20));
    }

    @Test
    void anonymous_cache_should_fallback_when_cached_parent_is_deleted() throws Exception {
        PostRepository postRepository = mock(PostRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(threadRepository.findById(7L)).thenReturn(Optional.of(normalThread()));

        Instant updatedAt = Instant.parse("2026-10-18T00:00:00Z");
        PostDto dto = new PostDto();
        dto.setId(1L);
        dto.setThreadId(7L);
        dto.setReplyToPostId(2L);
        dto.setUpdatedAt(updatedAt);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        when(valueOperations.get(anyString())).thenReturn(mapper.writeValueAsString(new Object() {
            public final List<PostDto> items = List.of(dto);
            public final long total = 1L;
        }));

        Post child = new Post();
        child.setId(1L);
        child.setThreadId(7L);
        child.setStatus("NORMAL");
        child.setUpdatedAt(updatedAt);
        Post deletedParent = new Post();
        deletedParent.setId(2L);
        deletedParent.setThreadId(7L);
        deletedParent.setStatus("DELETED");
        deletedParent.setUpdatedAt(updatedAt);
        when(postRepository.findAllById(List.of(1L))).thenReturn(List.of(child));
        when(postRepository.findAllById(List.of(2L))).thenReturn(List.of(deletedParent));
        when(postRepository.findByThreadAscView(7L, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PostService service = new PostService(postRepository, threadRepository, userRepository, redisTemplate, mapper);

        assertThat(service.listByThread(7L, 1, 20).getContent()).isEmpty();
        verify(postRepository).findByThreadAscView(7L, PageRequest.of(0, 20));
    }

    @Test
    void anonymous_cache_validation_failure_should_propagate_database_error() throws Exception {
        PostRepository postRepository = mock(PostRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(threadRepository.findById(7L)).thenReturn(Optional.of(normalThread()));
        Instant updatedAt = Instant.parse("2026-10-18T00:00:00Z");
        PostDto dto = new PostDto();
        dto.setId(1L);
        dto.setThreadId(7L);
        dto.setUpdatedAt(updatedAt);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        when(valueOperations.get(anyString())).thenReturn(mapper.writeValueAsString(new Object() {
            public final List<PostDto> items = List.of(dto);
            public final long total = 1L;
        }));
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(postRepository.findAllById(List.of(1L))).thenThrow(failure);

        PostService service = new PostService(postRepository, threadRepository, userRepository, redisTemplate, mapper);

        assertThatThrownBy(() -> service.listByThread(7L, 1, 20)).isSameAs(failure);
        verify(postRepository, never()).findByThreadAscView(anyLong(), any(PageRequest.class));
    }

    @Test
    void create_should_evict_thread_posts_cache() {
        PostRepository postRepository = mock(PostRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(threadRepository.findByIdForInteraction(7L)).thenReturn(Optional.of(normalThread()));
        when(userRepository.existsById(1L)).thenReturn(true);
        Cursor<String> cursor = mock(Cursor.class);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("cache:posts:thread:7:1:20");

        Post saved = new Post();
        saved.setId(10L);
        saved.setThreadId(7L);
        saved.setAuthorId(1L);
        saved.setContentMd("Hi");
        when(postRepository.save(any(Post.class))).thenReturn(saved);

        PostQueryView view = mock(PostQueryView.class);
        when(view.getId()).thenReturn(10L);
        when(view.getThreadId()).thenReturn(7L);
        when(view.getThreadTitle()).thenReturn("Thread");
        when(view.getAuthorId()).thenReturn(1L);
        when(view.getAuthorUsername()).thenReturn("user");
        when(view.getAuthorNickname()).thenReturn("User");
        when(view.getAuthorAvatarUrl()).thenReturn("");
        when(view.getContent()).thenReturn("Hi");
        when(postRepository.findViewById(10L)).thenReturn(Optional.of(view));

        PostService service = new PostService(postRepository, threadRepository, userRepository, redisTemplate, null);
        CreatePostRequest req = new CreatePostRequest();
        req.setContentMd("Hi");

        service.create(1L, 7L, req);

        verify(redisTemplate, times(1)).delete(List.of("cache:posts:thread:7:1:20"));
        verify(redisTemplate).scan(org.mockito.ArgumentMatchers.argThat(options ->
                "cache:posts:thread:7:*".equals(options.getPattern())));
        verify(redisTemplate).scan(org.mockito.ArgumentMatchers.argThat(options ->
                "cache:threads:list:*".equals(options.getPattern())));
        verify(redisTemplate).delete("cache:threads:detail:7");
        verify(cursor, times(2)).close();
    }

    @Test
    void create_should_not_evict_any_cache_before_transaction_commit_or_after_rollback() {
        PostRepository postRepository = mock(PostRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(threadRepository.findByIdForInteraction(7L)).thenReturn(Optional.of(normalThread()));
        when(userRepository.existsById(1L)).thenReturn(true);
        Post saved = new Post();
        saved.setId(10L);
        saved.setThreadId(7L);
        saved.setAuthorId(1L);
        saved.setContentMd("Hi");
        when(postRepository.save(any(Post.class))).thenReturn(saved);
        PostQueryView view = mock(PostQueryView.class);
        when(view.getId()).thenReturn(10L);
        when(view.getThreadId()).thenReturn(7L);
        when(postRepository.findViewById(10L)).thenReturn(Optional.of(view));
        PostService service = new PostService(postRepository, threadRepository, userRepository, redisTemplate, null);
        CreatePostRequest req = new CreatePostRequest();
        req.setContentMd("Hi");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.create(1L, 7L, req);
            verify(redisTemplate, never()).scan(any());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verify(redisTemplate, never()).delete(anyString());
            verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.anyList());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
