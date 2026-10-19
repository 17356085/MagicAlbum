package com.example.demo.search.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import com.example.demo.threads.service.ThreadService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ThreadSearchServiceTest {
    private final ThreadService threads = mock(ThreadService.class);
    private final ThreadSearchProjectionService owner = mock(ThreadSearchProjectionService.class);
    private final ContentWriteGateService control = mock(ContentWriteGateService.class);
    private final ContentSearchIndex index = mock(ContentSearchIndex.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ContentSearchIndex> provider = mock(ObjectProvider.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ThreadSearchService service = new ThreadSearchService(threads, owner, control, provider, metrics);

    @Test
    void unavailable_index_returns_authoritative_database_results() {
        ready();
        when(index.search(anyString(), anyString(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("offline"));
        ThreadDto dto = new ThreadDto();
        dto.setId(11L);
        Page<ThreadDto> database = new PageImpl<>(List.of(dto));
        when(owner.databaseSearch("needle", null, null, 1, 10)).thenReturn(database);
        assertThat(service.list("needle", null, null, 1, 10)).isSameAs(database);
        assertThat(metrics.counter("content.search.fallback", "reason", "unavailable").count()).isEqualTo(1);
    }

    @Test
    void hidden_or_changed_hit_falls_back_instead_of_returning_stale_content() {
        ready();
        when(index.search(ContentSearchIndex.ALIAS, "needle", "tag", 3L, 1, 10))
                .thenReturn(new ContentSearchIndex.SearchPage(List.of(new ContentSearchIndex.SearchHit(7, 1)), 1));
        when(owner.visibleResult(7, 1)).thenReturn(Optional.empty());
        when(owner.databaseSearch(" needle ", "TAG", 3L, 0, 20)).thenReturn(Page.empty());
        assertThat(service.list(" needle ", "TAG", 3L, 0, 20)).isEmpty();
        verify(owner).databaseSearch(" needle ", "TAG", 3L, 0, 20);
        verifyNoInteractions(threads);
    }

    @Test
    void current_hits_return_only_owner_dtos_with_existing_pagination() {
        ready();
        ThreadDto current = new ThreadDto();
        current.setId(7L);
        current.setTitle("authoritative title");
        when(index.search(ContentSearchIndex.ALIAS, "needle", null, null, 2, 10))
                .thenReturn(new ContentSearchIndex.SearchPage(List.of(new ContentSearchIndex.SearchHit(7, 2)), 11));
        when(owner.visibleResult(7, 2)).thenReturn(Optional.of(current));
        Page<ThreadDto> result = service.list("needle", null, null, 2, 10);
        assertThat(result.getContent()).containsExactly(current);
        assertThat(result.getTotalElements()).isEqualTo(11);
        assertThat(result.getNumber()).isEqualTo(1);
        verify(owner, never()).databaseSearch(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void a_divergent_alias_invalidates_only_the_observed_pointer_and_uses_the_database() {
        ready();
        when(index.activeIndex()).thenReturn(Optional.of("tongpin-threads-vother"));
        when(owner.databaseSearch("needle", null, null, 1, 10)).thenReturn(Page.empty());

        assertThat(service.list("needle", null, null, 1, 10)).isEmpty();

        verify(control).invalidateIfActive("tongpin-threads-vtest");
        verify(index, never()).search(anyString(), anyString(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void unready_search_does_not_query_a_partial_index() {
        when(provider.getIfAvailable()).thenReturn(index);
        when(control.snapshot()).thenReturn(new ContentWriteGateService.Snapshot(null, false, false, null));
        when(owner.databaseSearch("needle", null, null, 1, 10)).thenReturn(Page.empty());
        assertThat(service.list("needle", null, null, 1, 10)).isEmpty();
        verifyNoInteractions(index);
    }

    private void ready() {
        when(provider.getIfAvailable()).thenReturn(index);
        when(control.snapshot()).thenReturn(new ContentWriteGateService.Snapshot("tongpin-threads-vtest", true, false, null));
        when(index.activeIndex()).thenReturn(Optional.of("tongpin-threads-vtest"));
    }
}
