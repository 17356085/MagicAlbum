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
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceCacheTest {

    @Test
    void listByThread_should_cache_result() {
        PostRepository postRepository = mock(PostRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(threadRepository.existsById(7L)).thenReturn(true);

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
        when(threadRepository.existsById(7L)).thenReturn(true);

        PostDto dto = new PostDto();
        dto.setId(1L);
        dto.setContent("Cached");
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
    void create_should_evict_thread_posts_cache() {
        PostRepository postRepository = mock(PostRepository.class);
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(threadRepository.existsById(7L)).thenReturn(true);
        when(userRepository.existsById(1L)).thenReturn(true);
        when(redisTemplate.keys("cache:posts:thread:7:*")).thenReturn(Set.of("cache:posts:thread:7:1:20"));

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

        verify(redisTemplate, times(1)).delete(Set.of("cache:posts:thread:7:1:20"));
    }
}
