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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SectionServiceTest {

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

    private Section section(Long id, String name, String slug) {
        Section section = new Section();
        section.setName(name);
        section.setSlug(slug);
        section.setVisible(true);
        section.setCreatedAt(Instant.now());
        return section;
    }
}
