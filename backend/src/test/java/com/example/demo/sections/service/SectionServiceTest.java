package com.example.demo.sections.service;

import com.example.demo.common.RedisKeys;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SectionServiceTest {

    private static final String GENERATION = "11111111-1111-1111-1111-111111111111";
    private static final String NEXT_GENERATION = "22222222-2222-2222-2222-222222222222";

    @Test
    void list_should_reuse_cache_for_equivalent_normalized_pagination() {
        for (int[] pagination : new int[][]{{0, 101, 100}, {-5, 0, 1}}) {
            SectionRepository repository = mock(SectionRepository.class);
            RedisFixture fixture = redisFixture(GENERATION);
            PageRequest effectivePage = PageRequest.of(0, pagination[2]);
            when(repository.search(eq("tech"), eq(effectivePage)))
                    .thenReturn(new PageImpl<>(List.of(section(1L, "Tech", "tech")), effectivePage, 1));
            SectionService service = new SectionService(repository, fixture.redis(), null);

            service.list(" tech ", pagination[0], pagination[1]);
            Page<Section> cached = service.list("tech", 1, pagination[2]);

            assertThat(cached.getContent()).extracting(Section::getName).containsExactly("Tech");
            assertThat(cached.getNumber()).isZero();
            assertThat(cached.getSize()).isEqualTo(pagination[2]);
            verify(repository, times(1)).search("tech", effectivePage);
            assertThat(fixture.entries()).containsKey(
                    RedisKeys.sectionsList(GENERATION, "tech", 1, pagination[2]));
        }
    }

    @Test
    void list_should_initialize_missing_generation_with_setnx_and_use_actual_value() {
        SectionRepository repository = mock(SectionRepository.class);
        RedisFixture fixture = redisFixture(null);
        when(repository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(section(1L, "Tech", "tech")), PageRequest.of(0, 20), 1));

        SectionService service = new SectionService(repository, fixture.redis(), null);
        service.list(null, 1, 20);

        String generation = fixture.entries().get(RedisKeys.sectionsListGeneration());
        assertThat(generation).isNotBlank();
        assertThat(UUID.fromString(generation).toString()).isEqualTo(generation);
        verify(fixture.values()).setIfAbsent(eq(RedisKeys.sectionsListGeneration()), anyString());
        assertThat(fixture.entries()).containsKey(RedisKeys.sectionsList(generation, null, 1, 20));
    }

    @Test
    void list_should_retry_generation_after_setnx_race_and_use_winner_namespace() {
        SectionRepository repository = mock(SectionRepository.class);
        RedisFixture fixture = redisFixture(null);
        when(fixture.values().setIfAbsent(eq(RedisKeys.sectionsListGeneration()), anyString()))
                .thenAnswer(invocation -> {
                    fixture.entries().putIfAbsent(RedisKeys.sectionsListGeneration(), NEXT_GENERATION);
                    return false;
                });
        when(repository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(section(1L, "Tech", "tech")), PageRequest.of(0, 20), 1));

        SectionService service = new SectionService(repository, fixture.redis(), null);
        service.list(null, 1, 20);

        assertThat(fixture.entries()).containsKey(RedisKeys.sectionsList(NEXT_GENERATION, null, 1, 20));
        assertThat(fixture.entries()).doesNotContainKey(
                RedisKeys.sectionsList(GENERATION, null, 1, 20));
    }

    @Test
    void list_should_bypass_cache_when_generation_is_missing_after_initialization() {
        SectionRepository repository = mock(SectionRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeys.sectionsListGeneration())).thenReturn(null);
        when(values.setIfAbsent(eq(RedisKeys.sectionsListGeneration()), anyString())).thenReturn(false);
        when(repository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        SectionService service = new SectionService(repository, redis, null);
        service.list(null, 1, 20);

        verify(repository).search(eq(null), any(PageRequest.class));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void list_should_bypass_cache_without_writing_when_redis_read_fails() {
        SectionRepository repository = mock(SectionRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeys.sectionsListGeneration())).thenReturn(GENERATION);
        when(values.get(RedisKeys.sectionsList(GENERATION, null, 1, 20)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(repository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        SectionService service = new SectionService(repository, redis, null);
        service.list(null, 1, 20);

        verify(repository).search(eq(null), any(PageRequest.class));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void stale_read_writes_only_old_namespace_and_cannot_overwrite_new_generation_cache() throws Exception {
        SectionRepository repository = mock(SectionRepository.class);
        RedisFixture fixture = redisFixture(GENERATION);
        CountDownLatch staleQueryStarted = new CountDownLatch(1);
        CountDownLatch releaseStaleQuery = new CountDownLatch(1);
        AtomicInteger repositoryCalls = new AtomicInteger();
        Page<Section> stalePage = page(section(1L, "Old", "old"));
        Page<Section> currentPage = page(section(1L, "New", "new"));
        when(repository.search(any(), any(PageRequest.class))).thenAnswer(invocation -> {
            if (repositoryCalls.getAndIncrement() == 0) {
                staleQueryStarted.countDown();
                assertThat(releaseStaleQuery.await(5, TimeUnit.SECONDS)).isTrue();
                return stalePage;
            }
            return currentPage;
        });

        SectionService service = new SectionService(repository, fixture.redis(), null);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var staleRead = executor.submit(() -> service.list(null, 1, 20));
            assertThat(staleQueryStarted.await(5, TimeUnit.SECONDS)).isTrue();

            fixture.entries().put(RedisKeys.sectionsListGeneration(), NEXT_GENERATION);
            assertThat(service.list(null, 1, 20).getContent())
                    .extracting(Section::getName).containsExactly("New");
            assertThat(fixture.entries()).containsKey(RedisKeys.sectionsList(NEXT_GENERATION, null, 1, 20));

            releaseStaleQuery.countDown();
            staleRead.get(5, TimeUnit.SECONDS);

            assertThat(service.list(null, 1, 20).getContent())
                    .extracting(Section::getName).containsExactly("New");
            assertThat(repositoryCalls).hasValue(2);
            assertThat(fixture.entries()).containsKey(RedisKeys.sectionsList(NEXT_GENERATION, null, 1, 20));
        } finally {
            releaseStaleQuery.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void list_should_cache_result_in_redis() {
        SectionRepository repository = mock(SectionRepository.class);
        RedisFixture fixture = redisFixture(GENERATION);
        Section section = section(1L, "Tech", "tech");
        when(repository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(section), PageRequest.of(0, 20), 1));

        SectionService service = new SectionService(repository, fixture.redis(), null);
        Page<Section> result = service.list(null, 1, 20);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        verify(repository, times(1)).search(any(), any(PageRequest.class));
        verify(fixture.values(), times(1)).set(
                eq(RedisKeys.sectionsList(GENERATION, null, 1, 20)), anyString(), any(Duration.class));
    }

    @Test
    void list_should_return_from_cache_when_present() throws Exception {
        SectionRepository repository = mock(SectionRepository.class);
        RedisFixture fixture = redisFixture(GENERATION);
        Section section = section(2L, "Photo", "photo");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(new Object() {
            public final List<Section> items = List.of(section);
            public final long total = 1L;
        });
        fixture.entries().put(RedisKeys.sectionsList(GENERATION, null, 1, 20), json);

        SectionService service = new SectionService(repository, fixture.redis(), mapper);
        Page<Section> result = service.list(null, 1, 20);

        assertThat(result.getContent()).hasSize(1);
        verify(repository, times(0)).search(any(), any(PageRequest.class));
    }

    @Test
    void list_should_trim_query_before_repository_and_cache_key() {
        SectionRepository repository = mock(SectionRepository.class);
        RedisFixture fixture = redisFixture(GENERATION);
        when(repository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        SectionService service = new SectionService(repository, fixture.redis(), null);
        service.list("  tech  ", 1, 20);

        verify(fixture.values()).get(RedisKeys.sectionsList(GENERATION, "tech", 1, 20));
        verify(repository).search(eq("tech"), any(PageRequest.class));
    }

    @Test
    void list_should_replace_malformed_json_and_reuse_the_rebuilt_cache() {
        SectionRepository repository = mock(SectionRepository.class);
        RedisFixture fixture = redisFixture(GENERATION);
        String key = RedisKeys.sectionsList(GENERATION, null, 1, 20);
        fixture.entries().put(key, "{invalid json");
        when(repository.search(any(), any(PageRequest.class)))
                .thenReturn(page(section(1L, "Tech", "tech")));

        SectionService service = new SectionService(repository, fixture.redis(), null);
        assertThat(service.list(null, 1, 20).getContent()).extracting(Section::getName).containsExactly("Tech");
        assertThat(service.list(null, 1, 20).getContent()).extracting(Section::getName).containsExactly("Tech");
        verify(fixture.redis()).delete(key);
        verify(repository, times(1)).search(eq(null), any(PageRequest.class));
    }

    @Test
    void list_should_fallback_and_delete_cache_when_cached_section_is_missing_id() throws Exception {
        SectionRepository repository = mock(SectionRepository.class);
        RedisFixture fixture = redisFixture(GENERATION);
        fixture.entries().put(RedisKeys.sectionsList(GENERATION, null, 1, 20),
                "{\"items\":[{\"name\":\"Tech\",\"slug\":\"tech\",\"visible\":true}],\"total\":1}");
        when(repository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        SectionService service = new SectionService(repository, fixture.redis(), null);
        assertThat(service.list(null, 1, 20)).isNotNull();

        verify(fixture.redis()).delete(RedisKeys.sectionsList(GENERATION, null, 1, 20));
        verify(repository).search(eq(null), any(PageRequest.class));
    }

    @Test
    void list_should_fallback_and_delete_cache_when_cached_total_is_negative() throws Exception {
        SectionRepository repository = mock(SectionRepository.class);
        RedisFixture fixture = redisFixture(GENERATION);
        fixture.entries().put(RedisKeys.sectionsList(GENERATION, null, 1, 20), "{\"items\":[],\"total\":-1}");
        when(repository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        SectionService service = new SectionService(repository, fixture.redis(), null);
        assertThat(service.list(null, 1, 20)).isNotNull();

        verify(fixture.redis()).delete(RedisKeys.sectionsList(GENERATION, null, 1, 20));
        verify(repository).search(eq(null), any(PageRequest.class));
    }

    private RedisFixture redisFixture(String generation) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> entries = new ConcurrentHashMap<>();
        if (generation != null) {
            entries.put(RedisKeys.sectionsListGeneration(), generation);
        }
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        doAnswer(call -> {
            entries.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        doAnswer(call -> entries.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null)
                .when(values).setIfAbsent(anyString(), anyString());
        doAnswer(call -> {
            entries.remove(call.getArgument(0));
            return true;
        }).when(redis).delete(anyString());
        return new RedisFixture(redis, values, entries);
    }

    private Page<Section> page(Section section) {
        return new PageImpl<>(List.of(section), PageRequest.of(0, 20), 1);
    }

    private Section section(Long id, String name, String slug) {
        Section section = new Section();
        section.setId(id);
        section.setName(name);
        section.setSlug(slug);
        section.setVisible(true);
        section.setCreatedAt(Instant.now());
        return section;
    }

    private record RedisFixture(StringRedisTemplate redis,
                                ValueOperations<String, String> values,
                                Map<String, String> entries) {
    }
}
