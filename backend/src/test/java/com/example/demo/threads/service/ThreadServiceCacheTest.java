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
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThreadServiceCacheTest {

    @Test
    void delete_should_evict_list_pages_and_detail_cache() {
        ThreadRepository repository = mock(ThreadRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("cache:threads:list:all:newest:1:10");
        com.example.demo.threads.entity.Thread thread = new com.example.demo.threads.entity.Thread();
        thread.setId(7L);
        thread.setAuthorId(11L);
        when(repository.findById(7L)).thenReturn(Optional.of(thread));
        ObjectProvider<ThreadReadServiceMp> mp = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbit = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(repository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mp, false, rabbit, false, redis, null);

        service.delete(11L, 7L);

        assertThat(thread.getStatus()).isEqualTo("DELETED");
        verify(repository).save(thread);
        verify(redis).scan(org.mockito.ArgumentMatchers.argThat(options ->
                "cache:threads:list:*".equals(options.getPattern())));
        verify(redis).delete(List.of("cache:threads:list:all:newest:1:10"));
        verify(redis).delete("cache:threads:detail:7");
        verify(cursor).close();
    }

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
        dto.setSectionId(7L);
        dto.setAuthorId(11L);
        dto.setTitle("Cached");
        dto.setStatus("NORMAL");
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

    @Test
    void list_should_trim_query_before_repository_and_cache_key() {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(threadRepository.searchNewestView(any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);

        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, null);
        service.list("  tech  ", null, 7L, 1, 10);

        verify(valueOperations).get("cache:threads:list:tech::7:1:10");
        verify(threadRepository).searchNewestView(eq("tech"), isNull(), eq(7L), any(PageRequest.class));
    }

    @Test
    void list_should_trim_query_before_mp_reader_and_cache_key() {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        ThreadReadServiceMp mpReader = mock(ThreadReadServiceMp.class);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.example.demo.threads.entity.Thread> mpPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        mpPage.setTotal(0);
        when(mpReader.searchNewest(eq("tech"), eq(7L), eq(1), eq(10))).thenReturn(mpPage);
        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(mpReader);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);

        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, true, rabbitProvider, false, redisTemplate, null);
        service.list("  tech  ", null, 7L, 1, 10);

        verify(valueOperations).get("cache:threads:list:tech::7:1:10");
        verify(mpReader).searchNewest("tech", 7L, 1, 10);
    }

    @Test
    void list_should_fallback_when_cached_page_is_invalid_even_if_deletion_fails() throws Exception {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("{\"items\":[{\"id\":9,\"title\":\"Missing ownership\"}],\"total\":1}");
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisTemplate).delete("cache:threads:list:::7:1:10");
        when(threadRepository.searchNewestView(any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, null);

        assertThat(service.list(null, null, 7L, 1, 10)).isNotNull();

        verify(redisTemplate).delete("cache:threads:list:::7:1:10");
        verify(threadRepository).searchNewestView(isNull(), isNull(), eq(7L), any(PageRequest.class));
    }

    @Test
    void list_should_fallback_and_delete_cache_when_cached_total_is_negative() throws Exception {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("{\"items\":[],\"total\":-1}");
        when(threadRepository.searchNewestView(any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, null);

        assertThat(service.list(null, null, 7L, 1, 10)).isNotNull();

        verify(redisTemplate).delete("cache:threads:list:::7:1:10");
        verify(threadRepository).searchNewestView(isNull(), isNull(), eq(7L), any(PageRequest.class));
    }

    @Test
    void detail_should_fallback_and_delete_cache_when_cached_id_does_not_match_request() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        ThreadDto cached = validThreadDto(10L, "NORMAL");
        when(valueOperations.get("cache:threads:detail:9")).thenReturn(mapper.writeValueAsString(cached));

        ThreadRepository threadRepository = mock(ThreadRepository.class);
        com.example.demo.threads.entity.Thread entity = normalThread(9L);
        when(threadRepository.findById(9L)).thenReturn(java.util.Optional.of(entity));
        ThreadQueryView view = mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(9L);
        when(view.getSectionId()).thenReturn(7L);
        when(view.getAuthorId()).thenReturn(11L);
        when(view.getTitle()).thenReturn("Database");
        when(view.getStatus()).thenReturn("NORMAL");
        when(threadRepository.findViewById(9L)).thenReturn(java.util.Optional.of(view));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        MarkdownRenderService markdown = mock(MarkdownRenderService.class);
        when(markdown.renderWithCache(eq(9L), any(), anyString())).thenReturn("<p>Body</p>");
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class), markdown,
                mpProvider, false, rabbitProvider, false, redisTemplate, mapper);

        assertThat(service.getById(9L).getTitle()).isEqualTo("Database");

        verify(redisTemplate).delete("cache:threads:detail:9");
        verify(threadRepository).findViewById(9L);
    }

    @Test
    void detail_should_fallback_and_delete_cache_when_cached_status_is_not_normal() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        when(valueOperations.get("cache:threads:detail:9"))
                .thenReturn(mapper.writeValueAsString(validThreadDto(9L, "HIDDEN")));

        ThreadRepository threadRepository = mock(ThreadRepository.class);
        com.example.demo.threads.entity.Thread entity = normalThread(9L);
        when(threadRepository.findById(9L)).thenReturn(java.util.Optional.of(entity));
        ThreadQueryView view = mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(9L);
        when(view.getSectionId()).thenReturn(7L);
        when(view.getAuthorId()).thenReturn(11L);
        when(view.getTitle()).thenReturn("Database");
        when(view.getStatus()).thenReturn("NORMAL");
        when(threadRepository.findViewById(9L)).thenReturn(java.util.Optional.of(view));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        MarkdownRenderService markdown = mock(MarkdownRenderService.class);
        when(markdown.renderWithCache(eq(9L), any(), anyString())).thenReturn("<p>Body</p>");
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class), markdown,
                mpProvider, false, rabbitProvider, false, redisTemplate, mapper);

        assertThat(service.getById(9L).getTitle()).isEqualTo("Database");

        verify(redisTemplate).delete("cache:threads:detail:9");
        verify(threadRepository).findViewById(9L);
    }

    @Test
    void delete_should_evict_thread_caches_after_commit_only() {
        ThreadRepository repository = mock(ThreadRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);
        com.example.demo.threads.entity.Thread thread = normalThread(7L);
        when(repository.findById(7L)).thenReturn(java.util.Optional.of(thread));
        ObjectProvider<ThreadReadServiceMp> mp = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbit = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(repository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mp, false, rabbit, false, redis, null);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(11L, 7L);
            verify(redis, never()).scan(any(ScanOptions.class));
            verify(redis, never()).delete(anyString());

            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCommit();
            verify(redis).scan(any(ScanOptions.class));
            verify(redis).delete("cache:threads:detail:7");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void delete_should_not_evict_thread_caches_after_rollback() {
        ThreadRepository repository = mock(ThreadRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        com.example.demo.threads.entity.Thread thread = normalThread(7L);
        when(repository.findById(7L)).thenReturn(java.util.Optional.of(thread));
        ObjectProvider<ThreadReadServiceMp> mp = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbit = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(repository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mp, false, rabbit, false, redis, null);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(11L, 7L);
            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(redis, never()).scan(any(ScanOptions.class));
            verify(redis, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ThreadDto validThreadDto(Long id, String status) {
        ThreadDto dto = new ThreadDto();
        dto.setId(id);
        dto.setSectionId(7L);
        dto.setAuthorId(11L);
        dto.setTitle("Cached");
        dto.setStatus(status);
        return dto;
    }

    private com.example.demo.threads.entity.Thread normalThread(Long id) {
        com.example.demo.threads.entity.Thread thread = new com.example.demo.threads.entity.Thread();
        thread.setId(id);
        thread.setSectionId(7L);
        thread.setAuthorId(11L);
        thread.setTitle("Database");
        thread.setContentMd("Body");
        thread.setStatus("NORMAL");
        return thread;
    }
}
