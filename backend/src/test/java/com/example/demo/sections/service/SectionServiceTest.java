package com.example.demo.sections.service;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SectionServiceTest {

    @Test
    void list_should_reuse_cache_for_equivalent_normalized_pagination() {
        for (int[] pagination : new int[][]{{0, 101, 100}, {-5, 0, 1}}) {
            SectionRepository repository = mock(SectionRepository.class);
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            Map<String, String> entries = new HashMap<>();
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
            doAnswer(call -> {
                entries.put(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(values).set(anyString(), anyString(), any(Duration.class));
            PageRequest effectivePage = PageRequest.of(0, pagination[2]);
            when(repository.search(eq("tech"), eq(effectivePage)))
                    .thenReturn(new PageImpl<>(List.of(section(1L, "Tech", "tech")), effectivePage, 1));
            SectionService service = new SectionService(repository, redis, null);

            service.list(" tech ", pagination[0], pagination[1]);
            Page<Section> cached = service.list("tech", 1, pagination[2]);

            assertThat(cached.getContent()).extracting(Section::getName).containsExactly("Tech");
            assertThat(cached.getNumber()).isZero();
            assertThat(cached.getSize()).isEqualTo(pagination[2]);
            verify(repository, times(1)).search("tech", effectivePage);
            assertThat(entries).hasSize(1).containsKey("cache:sections:list:tech:1:" + pagination[2]);
        }
    }

    @Test
    void list_should_cache_result_in_redis() {
        SectionRepository sectionRepository = mock(SectionRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        Section section = section(1L, "Tech", "tech");
        when(sectionRepository.search(any(), any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(section), PageRequest.of(0, 20), 1));

        SectionService service = new SectionService(sectionRepository, redisTemplate, null);
        Page<Section> result = service.list(null, 1, 20);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        verify(sectionRepository, times(1)).search(any(), any(PageRequest.class));
        verify(valueOperations, times(1)).set(anyString(), anyString(), any());
    }

    @Test
    void list_should_return_from_cache_when_present() throws Exception {
        SectionRepository sectionRepository = mock(SectionRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Section section = section(2L, "Photo", "photo");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(new Object() {
            public final List<Section> items = List.of(section);
            public final long total = 1L;
        });
        when(valueOperations.get(anyString())).thenReturn(json);

        SectionService service = new SectionService(sectionRepository, redisTemplate, mapper);
        Page<Section> result = service.list(null, 1, 20);

        assertThat(result.getContent()).hasSize(1);
        verify(sectionRepository, times(0)).search(any(), any(PageRequest.class));
    }

    @Test
    void list_should_trim_query_before_repository_and_cache_key() {
        SectionRepository sectionRepository = mock(SectionRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(sectionRepository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        SectionService service = new SectionService(sectionRepository, redisTemplate, null);
        service.list("  tech  ", 1, 20);

        verify(valueOperations).get("cache:sections:list:tech:1:20");
        verify(sectionRepository).search(eq("tech"), any(PageRequest.class));
    }

    @Test
    void list_should_fallback_and_delete_cache_when_cached_section_is_missing_id() throws Exception {
        SectionRepository sectionRepository = mock(SectionRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(
                "{\"items\":[{\"name\":\"Tech\",\"slug\":\"tech\",\"visible\":true}],\"total\":1}");
        when(sectionRepository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        SectionService service = new SectionService(sectionRepository, redisTemplate, null);
        assertThat(service.list(null, 1, 20)).isNotNull();

        verify(redisTemplate).delete("cache:sections:list::1:20");
        verify(sectionRepository).search(eq(null), any(PageRequest.class));
    }

    @Test
    void list_should_fallback_and_delete_cache_when_cached_total_is_negative() throws Exception {
        SectionRepository sectionRepository = mock(SectionRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("{\"items\":[],\"total\":-1}");
        when(sectionRepository.search(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        SectionService service = new SectionService(sectionRepository, redisTemplate, null);
        assertThat(service.list(null, 1, 20)).isNotNull();

        verify(redisTemplate).delete("cache:sections:list::1:20");
        verify(sectionRepository).search(eq(null), any(PageRequest.class));
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
}
