package com.example.demo.search.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchRebuildServiceTest {
    private final ContentWriteGateService control = mock(ContentWriteGateService.class);
    private final ThreadSearchProjectionService owner = mock(ThreadSearchProjectionService.class);
    private final ContentSearchIndex index = mock(ContentSearchIndex.class);
    private final SearchRebuildService service = new SearchRebuildService(control, owner, index);
    private final ThreadSearchDocument tombstone = new ThreadSearchDocument(7, 4, false, "", "", List.of(), 1L,
            Instant.parse("2026-10-19T00:00:00Z"), "event-id", "THREAD_DELETED");

    @Test
    void switches_only_after_all_documents_including_tombstones_have_been_compared() {
        when(owner.scanAfter(0, 20)).thenReturn(List.of(tombstone));
        when(owner.scanAfter(7, 20)).thenReturn(List.of());
        when(index.count(anyString())).thenReturn(1L);
        when(index.read(anyString(), eq(7L))).thenReturn(Optional.of(tombstone));
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; })
                .when(control).finish(anyString(), anyString(), any(Runnable.class));
        var result = service.rebuild();
        assertThat(result.documents()).isEqualTo(1);
        assertThat(result.verified()).isEqualTo(1);
        var order = inOrder(control, index);
        order.verify(control).begin(anyString());
        order.verify(index).create(result.index());
        order.verify(index).put(result.index(), tombstone);
        order.verify(index).refresh(result.index());
        order.verify(index).count(result.index());
        order.verify(index).read(result.index(), 7);
        order.verify(control).finish(anyString(), eq(result.index()), any(Runnable.class));
        order.verify(index).switchAlias(result.index());
        order.verify(control).abort(anyString());
    }

    @Test
    void reconciles_an_applied_alias_with_a_lost_response_without_switching_twice() {
        when(owner.scanAfter(0, 20)).thenReturn(List.of());
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; })
                .when(control).finish(anyString(), anyString(), any(Runnable.class));
        doAnswer(call -> {
            when(index.activeIndex()).thenReturn(Optional.of(call.getArgument(0)));
            throw new IllegalStateException("lost response");
        }).when(index).switchAlias(anyString());

        var result = service.rebuild();

        verify(index, times(1)).switchAlias(result.index());
        verify(control, times(2)).finish(anyString(), eq(result.index()), any(Runnable.class));
        verify(control, never()).invalidateForOwner(anyString());
        verify(control).abort(anyString());
    }

    @Test
    void an_unresolved_switch_disables_readiness_before_releasing_the_gate() {
        when(owner.scanAfter(0, 20)).thenReturn(List.of());
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; })
                .when(control).finish(anyString(), anyString(), any(Runnable.class));
        doThrow(new IllegalStateException("offline")).when(index).switchAlias(anyString());
        when(index.activeIndex()).thenReturn(Optional.empty());
        when(control.snapshot()).thenReturn(new ContentWriteGateService.Snapshot("old", false, true, null));

        assertThatThrownBy(service::rebuild).hasMessage("Search rebuild requires reconciliation");

        var order = inOrder(control);
        order.verify(control).invalidateForOwner(anyString());
        order.verify(control).abort(anyString());
        verify(index, times(1)).switchAlias(anyString());
    }

    @Test
    void a_failed_verification_keeps_the_old_alias_and_releases_only_its_own_gate() {
        when(owner.scanAfter(0, 20)).thenReturn(List.of(tombstone));
        when(owner.scanAfter(7, 20)).thenReturn(List.of());
        when(index.count(anyString())).thenReturn(1L);
        when(index.read(anyString(), eq(7L))).thenReturn(Optional.empty());
        assertThatThrownBy(service::rebuild).isInstanceOf(IllegalStateException.class);
        verify(index, never()).switchAlias(anyString());
        verify(control, never()).finish(anyString(), anyString(), any(Runnable.class));
        verify(control).abort(anyString());
    }
}
