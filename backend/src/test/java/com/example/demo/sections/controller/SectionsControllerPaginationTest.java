package com.example.demo.sections.controller;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.service.SectionService;
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

class SectionsControllerPaginationTest {

    @Test
    void list_reports_page_metadata_after_service_normalizes_request() {
        SectionService sectionService = mock(SectionService.class);
        when(sectionService.list(isNull(), eq(0), eq(100)))
                .thenReturn(new PageImpl<>(List.<Section>of(), PageRequest.of(0, 100), 0));

        var body = new SectionsController(sectionService)
                .list(null, 0, 100)
                .getBody();

        assertThat(body).containsEntry("page", 1).containsEntry("size", 100);
        verify(sectionService).list(isNull(), eq(0), eq(100));
    }
}
