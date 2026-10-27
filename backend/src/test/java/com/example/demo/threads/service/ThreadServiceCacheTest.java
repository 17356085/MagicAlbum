package com.example.demo.threads.service;

import com.example.demo.common.RedisKeys;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.event.service.ContentEventRecorder;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
        when(repository.findByIdForInteraction(7L)).thenReturn(Optional.of(thread));
        ObjectProvider<ThreadReadServiceMp> mp = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbit = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(repository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mp, false, rabbit, false, redis, null,
                mock(ContentEventRecorder.class));

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

        ThreadService service = new ThreadService(threadRepository, sectionRepository, markdownRenderService, mpProvider, false,
                rabbitProvider, false, redisTemplate, null, mock(ContentEventRecorder.class));
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
        Instant updatedAt = Instant.parse("2026-10-18T00:00:00Z");
        dto.setUpdatedAt(updatedAt);
        com.example.demo.threads.entity.Thread authoritative = normalThread(9L);
        authoritative.setUpdatedAt(updatedAt);
        when(threadRepository.findAllById(List.of(9L))).thenReturn(List.of(authoritative));
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

        ThreadService service = new ThreadService(threadRepository, sectionRepository, markdownRenderService, mpProvider, false,
                rabbitProvider, false, redisTemplate, mapper, mock(ContentEventRecorder.class));
        var result = service.list(null, null, 1, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("Cached");
        verify(threadRepository, times(0)).searchNewestView(any(), any(), any(), any(PageRequest.class));
    }

    @Test
    void list_should_fallback_when_authoritative_thread_is_hidden() throws Exception {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ThreadDto dto = validThreadDto(9L, "NORMAL");
        Instant updatedAt = Instant.parse("2026-10-18T00:00:00Z");
        dto.setUpdatedAt(updatedAt);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        when(valueOperations.get(anyString())).thenReturn(mapper.writeValueAsString(new Object() {
            public final List<ThreadDto> items = List.of(dto);
            public final long total = 1L;
        }));
        com.example.demo.threads.entity.Thread hidden = normalThread(9L);
        hidden.setStatus("HIDDEN");
        hidden.setUpdatedAt(updatedAt);
        when(threadRepository.findAllById(List.of(9L))).thenReturn(List.of(hidden));
        when(threadRepository.searchNewestView(any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, mapper,
                mock(ContentEventRecorder.class));

        assertThat(service.list(null, null, 7L, 1, 10).getContent()).isEmpty();
        verify(threadRepository).searchNewestView(isNull(), isNull(), eq(7L), any(PageRequest.class));
    }

    @Test
    void list_should_propagate_authoritative_cache_validation_failure() throws Exception {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ThreadDto dto = validThreadDto(9L, "NORMAL");
        dto.setUpdatedAt(Instant.parse("2026-10-18T00:00:00Z"));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        when(valueOperations.get(anyString())).thenReturn(mapper.writeValueAsString(new Object() {
            public final List<ThreadDto> items = List.of(dto);
            public final long total = 1L;
        }));
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(threadRepository.findAllById(List.of(9L))).thenThrow(failure);

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, mapper,
                mock(ContentEventRecorder.class));

        assertThatThrownBy(() -> service.list(null, null, 7L, 1, 10)).isSameAs(failure);
        verify(threadRepository, never()).searchNewestView(any(), any(), any(), any(PageRequest.class));
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
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, null,
                mock(ContentEventRecorder.class));
        service.list("  tech  ", null, 7L, 1, 10);

        verify(valueOperations).get(RedisKeys.threadsList("tech", null, 7L, 1, 10));
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
                mock(MarkdownRenderService.class), mpProvider, true, rabbitProvider, false, redisTemplate, null,
                mock(ContentEventRecorder.class));
        service.list("  tech  ", null, 7L, 1, 10);

        verify(valueOperations).get(RedisKeys.threadsList("tech", null, 7L, 1, 10));
        verify(mpReader).searchNewest("tech", 7L, 1, 10);
    }

    @Test
    void mp_list_should_filter_currently_non_normal_rows_before_response_and_cache() throws Exception {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Map<String, String> cacheStore = new HashMap<>();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> cacheStore.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            cacheStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any());

        ThreadReadServiceMp mpReader = mock(ThreadReadServiceMp.class);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.example.demo.threads.entity.Thread> mpPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        mpPage.setRecords(List.of(normalThread(2L), normalThread(1L), normalThread(3L)));
        mpPage.setTotal(3L);
        when(mpReader.searchNewest(null, 7L, 1, 10)).thenReturn(mpPage);
        ThreadQueryView visibleView = projection(2L, "visible", "NORMAL");
        ThreadQueryView hiddenView = projection(1L, "hidden", "HIDDEN");
        ThreadQueryView deletedView = projection(3L, "deleted", "DELETED");
        when(threadRepository.findViewsByIdIn(List.of(2L, 1L, 3L))).thenReturn(List.of(
                visibleView, hiddenView, deletedView));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(mpReader);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, true, rabbitProvider, false,
                redisTemplate, mapper, mock(ContentEventRecorder.class));

        var result = service.list(null, null, 7L, 1, 10);

        assertThat(result.getContent()).extracting(ThreadDto::getId).containsExactly(2L);
        assertThat(result.getTotalElements()).isEqualTo(1L);
        String key = RedisKeys.threadsList(null, null, 7L, 1, 10);
        com.fasterxml.jackson.databind.JsonNode cached = mapper.readTree(cacheStore.get(key));
        assertThat(cached.get("total").asLong()).isEqualTo(3L);
        assertThat(cached.get("items")).hasSize(1);
        assertThat(cached.get("items").get(0).get("id").asLong()).isEqualTo(2L);
        assertThat(cached.toString()).doesNotContain("hidden", "deleted");
        verify(mpReader).searchNewest(null, 7L, 1, 10);
        verify(threadRepository).findViewsByIdIn(List.of(2L, 1L, 3L));
    }

    @Test
    void list_should_separate_colliding_query_and_tag_tuples_with_stateful_cache() {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Map<String, String> cacheStore = new HashMap<>();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> cacheStore.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            cacheStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any());

        ThreadQueryView view = mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(1L);
        when(view.getSectionId()).thenReturn(7L);
        when(view.getSectionName()).thenReturn("Tech");
        when(view.getAuthorId()).thenReturn(11L);
        when(view.getAuthorUsername()).thenReturn("alice");
        when(view.getAuthorNickname()).thenReturn("Alice");
        when(view.getAuthorAvatar()).thenReturn("/a.png");
        when(view.getTitle()).thenReturn("Expected");
        when(view.getContent()).thenReturn("World");
        when(view.getStatus()).thenReturn("NORMAL");
        when(threadRepository.searchNewestView(eq("a:b"), eq("c"), eq(7L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        when(threadRepository.searchNewestView(eq("a"), eq("b:c"), eq(7L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 10), 1));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);

        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, null,
                mock(ContentEventRecorder.class));

        assertThat(service.list("a:b", "c", 7L, 1, 10).getContent()).isEmpty();
        assertThat(service.list("a", "b:c", 7L, 1, 10).getContent())
                .extracting(ThreadDto::getTitle).containsExactly("Expected");
        assertThat(service.list("a:b", "c", 7L, 1, 10).getContent()).isEmpty();

        verify(threadRepository, times(1)).searchNewestView(eq("a:b"), eq("c"), eq(7L), any(PageRequest.class));
        verify(threadRepository, times(1)).searchNewestView(eq("a"), eq("b:c"), eq(7L), any(PageRequest.class));
        assertThat(cacheStore).containsKeys(
                RedisKeys.threadsList("a:b", "c", 7L, 1, 10),
                RedisKeys.threadsList("a", "b:c", 7L, 1, 10));
        assertThat(RedisKeys.threadsList("a:b", "c", 7L, 1, 10))
                .isNotEqualTo(RedisKeys.threadsList("a", "b:c", 7L, 1, 10));
    }

    @Test
    void list_should_use_one_effective_page_for_key_and_repository_when_page_is_non_positive() {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Map<String, String> cacheStore = new HashMap<>();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> cacheStore.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            cacheStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any());
        when(threadRepository.searchNewestView(isNull(), isNull(), eq(7L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, null,
                mock(ContentEventRecorder.class));

        service.list(null, null, 7L, 0, 10);
        service.list(null, null, 7L, -1, 10);
        service.list(null, null, 7L, 1, 10);

        String effectiveKey = RedisKeys.threadsList(null, null, 7L, 1, 10);
        verify(valueOperations, times(3)).get(effectiveKey);
        verify(threadRepository, times(1)).searchNewestView(isNull(), isNull(), eq(7L),
                eq(PageRequest.of(0, 10)));
    }

    @Test
    void list_should_fallback_when_cached_page_is_invalid_even_if_deletion_fails() throws Exception {
        ThreadRepository threadRepository = mock(ThreadRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("{\"items\":[{\"id\":9,\"title\":\"Missing ownership\"}],\"total\":1}");
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisTemplate).delete(RedisKeys.threadsList(null, null, 7L, 1, 10));
        when(threadRepository.searchNewestView(any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, null,
                mock(ContentEventRecorder.class));

        assertThat(service.list(null, null, 7L, 1, 10)).isNotNull();

        verify(redisTemplate).delete(RedisKeys.threadsList(null, null, 7L, 1, 10));
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
                mock(MarkdownRenderService.class), mpProvider, false, rabbitProvider, false, redisTemplate, null,
                mock(ContentEventRecorder.class));

        assertThat(service.list(null, null, 7L, 1, 10)).isNotNull();

        verify(redisTemplate).delete(RedisKeys.threadsList(null, null, 7L, 1, 10));
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
                mpProvider, false, rabbitProvider, false, redisTemplate, mapper,
                mock(ContentEventRecorder.class));

        assertThat(service.getById(9L).getTitle()).isEqualTo("Database");

        verify(redisTemplate).delete("cache:threads:detail:9");
        verify(threadRepository).findViewById(9L);
    }

    @Test
    void detail_should_fallback_when_cached_updated_at_is_stale_and_render_view_snapshot() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        Instant stale = Instant.parse("2026-10-18T00:00:00Z");
        ThreadDto cached = validThreadDto(9L, "NORMAL");
        cached.setUpdatedAt(stale);
        cached.setContent("Cached body");
        when(valueOperations.get("cache:threads:detail:9"))
                .thenReturn(mapper.writeValueAsString(cached));

        ThreadRepository threadRepository = mock(ThreadRepository.class);
        com.example.demo.threads.entity.Thread entity = normalThread(9L);
        entity.setUpdatedAt(Instant.parse("2026-10-18T00:00:01Z"));
        entity.setContentMd("Entity body");
        when(threadRepository.findById(9L)).thenReturn(Optional.of(entity));
        ThreadQueryView view = mock(ThreadQueryView.class);
        Instant viewUpdatedAt = Instant.parse("2026-10-18T00:00:02Z");
        when(view.getId()).thenReturn(9L);
        when(view.getSectionId()).thenReturn(7L);
        when(view.getAuthorId()).thenReturn(11L);
        when(view.getTitle()).thenReturn("Database");
        when(view.getContent()).thenReturn("View body");
        when(view.getUpdatedAt()).thenReturn(viewUpdatedAt);
        when(view.getStatus()).thenReturn("NORMAL");
        when(threadRepository.findViewById(9L)).thenReturn(Optional.of(view));

        ObjectProvider<ThreadReadServiceMp> mpProvider = mock(ObjectProvider.class);
        when(mpProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RabbitTemplate> rabbitProvider = mock(ObjectProvider.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        MarkdownRenderService markdown = mock(MarkdownRenderService.class);
        when(markdown.renderWithCache(9L, viewUpdatedAt, "View body"))
                .thenReturn("<p>View body</p>");
        ThreadService service = new ThreadService(threadRepository, mock(SectionRepository.class), markdown,
                mpProvider, false, rabbitProvider, false, redisTemplate, mapper,
                mock(ContentEventRecorder.class));

        ThreadDto result = service.getById(9L);

        assertThat(result.getTitle()).isEqualTo("Database");
        assertThat(result.getContentHtml()).isEqualTo("<p>View body</p>");
        verify(redisTemplate).delete("cache:threads:detail:9");
        verify(threadRepository).findViewById(9L);
        verify(markdown).renderWithCache(9L, viewUpdatedAt, "View body");
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
                mpProvider, false, rabbitProvider, false, redisTemplate, mapper,
                mock(ContentEventRecorder.class));

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
        when(repository.findByIdForInteraction(7L)).thenReturn(java.util.Optional.of(thread));
        ObjectProvider<ThreadReadServiceMp> mp = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbit = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(repository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mp, false, rabbit, false, redis, null,
                mock(ContentEventRecorder.class));

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
        when(repository.findByIdForInteraction(7L)).thenReturn(java.util.Optional.of(thread));
        ObjectProvider<ThreadReadServiceMp> mp = mock(ObjectProvider.class);
        ObjectProvider<RabbitTemplate> rabbit = mock(ObjectProvider.class);
        ThreadService service = new ThreadService(repository, mock(SectionRepository.class),
                mock(MarkdownRenderService.class), mp, false, rabbit, false, redis, null,
                mock(ContentEventRecorder.class));

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

    private ThreadQueryView projection(Long id, String title, String status) {
        ThreadQueryView view = mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(id);
        when(view.getSectionId()).thenReturn(7L);
        when(view.getSectionName()).thenReturn("Tech");
        when(view.getAuthorId()).thenReturn(11L);
        when(view.getAuthorUsername()).thenReturn("alice");
        when(view.getAuthorNickname()).thenReturn("Alice");
        when(view.getTitle()).thenReturn(title);
        when(view.getContent()).thenReturn("Body");
        when(view.getStatus()).thenReturn(status);
        return view;
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
