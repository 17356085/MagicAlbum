package com.example.demo.threads.service;

import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.service.mp.ThreadReadServiceMp;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThreadServiceCacheTest {

    @Test
    void list_should_cache_thread_page() {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        SectionRepository sectionRepository = mock(SectionRepository.class);
        MarkdownRenderService markdownRenderService = mock(MarkdownRenderService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);

        ThreadQueryView view = mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(1L);
        when(view.getSectionId()).thenReturn(7L);
        when(view.getSectionName()).thenReturn("Tech");
        when(view.getAuthorId()).thenReturn(11L);
        when(view.getAuthorUsername()).thenReturn("alice");
        when(view.getAuthorNickname()).thenReturn("Alice");
        when(view.getAuthorAvatar()).thenReturn("/a.png");
        when(view.getTitle()).thenReturn("Hello");
        when(view.getContent()).thenReturn("World");
        when(view.getStatus()).thenReturn("NORMAL");
        when(threadRepository.searchNewestView(any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 10), 1));

        ThreadService service = new ThreadService(threadRepository, sectionRepository, markdownRenderService, mpProvider, false, rabbitProvider, false, redisTemplate, null);
        var result = service.list(null, null, 1, 10);

        assertThat(result.getContent()).hasSize(1);
        verify(threadRepository, times(1)).searchNewestView(any(), any(), any(), any(PageRequest.class));
        verify(valueOperations, times(1)).set(anyString(), anyString(), any());
    }

    @Test
    void list_should_return_cached_thread_page() throws Exception {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        SectionRepository sectionRepository = mock(SectionRepository.class);
        MarkdownRenderService markdownRenderService = mock(MarkdownRenderService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ThreadDto dto = new ThreadDto();
        dto.setId(9L);
        dto.setTitle("Cached");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(new Object() {
            public final List<ThreadDto> items = List.of(dto);
            public final long total = 1L;
        });
        when(valueOperations.get(anyString())).thenReturn(json);

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);

        ThreadService service = new ThreadService(threadRepository, sectionRepository, markdownRenderService, mpProvider, false, rabbitProvider, false, redisTemplate, mapper);
        var result = service.list(null, null, 1, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("Cached");
        verify(threadRepository, times(0)).searchNewestView(any(), any(), any(), any(PageRequest.class));
    }
}
