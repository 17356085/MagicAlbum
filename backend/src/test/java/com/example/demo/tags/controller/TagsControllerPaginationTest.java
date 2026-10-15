package com.example.demo.tags.controller;

import com.example.demo.tags.dto.TagStatsDto;
import com.example.demo.tags.service.TagService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagsControllerPaginationTest {

    @Test
    void search_reports_page_metadata_after_service_clamps_request() {
        TagService tagService = mock(TagService.class);
        when(tagService.search(isNull(), isNull(Long.class), eq(0), eq(100)))
                .thenReturn(new PageImpl<>(List.<TagStatsDto>of(), PageRequest.of(0, 50), 0));

        var body = new TagsController(tagService)
                .list(null, null, 0, 100)
                .getBody();

        assertThat(body.page()).isEqualTo(1);
        assertThat(body.size()).isEqualTo(50);
        verify(tagService).search(isNull(), isNull(Long.class), eq(0), eq(100));
    }

    @Test
    void popular_reports_effective_size_instead_of_item_count() {
        TagService tagService = mock(TagService.class);
        List<TagStatsDto> items = List.of(new TagStatsDto(1L, "java", "thread", 3L));
        when(tagService.popular(isNull(Long.class), eq(100))).thenReturn(items);

        var body = new TagsController(tagService)
                .popular(null, 100)
                .getBody();

        assertThat(body.size()).isEqualTo(20);
        assertThat(body.total()).isEqualTo(1);
        verify(tagService).popular(isNull(Long.class), eq(100));
    }
}
