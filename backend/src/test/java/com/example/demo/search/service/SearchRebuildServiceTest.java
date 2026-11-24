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

    @Test
    void rollback_reuses_the_retained_index_and_writes_updated_rows_before_switching_alias() {
        String target = "tongpin-threads-v0123456789abcdef0123456789abcdef";
        ThreadSearchDocument updated = new ThreadSearchDocument(7, 5, true, "updated", "body", List.of("tag"),
                4L, Instant.parse("2026-10-20T00:00:00Z"), "event-id-2", "THREAD_UPSERTED");
        when(index.activeIndex()).thenReturn(Optional.of("tongpin-threads-vfedcba9876543210fedcba9876543210"));
        when(owner.scanAfter(0, 20)).thenReturn(List.of(updated));
        when(owner.scanAfter(7, 20)).thenReturn(List.of());
        when(index.count(target)).thenReturn(1L);
        when(index.read(target, 7L)).thenReturn(Optional.of(updated));
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; })
                .when(control).finish(anyString(), anyString(), any(Runnable.class));

        var result = service.rollback(target);

        assertThat(result.index()).isEqualTo(target);
        assertThat(result.documents()).isEqualTo(1);
        assertThat(result.verified()).isEqualTo(1);
        var order = inOrder(control, index);
        order.verify(control).begin(anyString());
        order.verify(index).activeIndex();
        order.verify(index).count(target);
        order.verify(index).put(target, updated);
        order.verify(index).refresh(target);
        order.verify(index).count(target);
        order.verify(index).read(target, 7);
        order.verify(control).finish(anyString(), eq(target), any(Runnable.class));
        order.verify(index).switchAlias(target);
        order.verify(control).abort(anyString());
        verify(index, never()).create(anyString());
    }

    @Test
    void rollback_rejects_alias_or_unrelated_index_before_acquiring_the_gate() {
        assertThatThrownBy(() -> service.rollback(ContentSearchIndex.ALIAS))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("managed physical index");
        assertThatThrownBy(() -> service.rollback("other-index"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("managed physical index");

        verifyNoInteractions(control, owner, index);
    }

    @Test
    void rollback_verification_failure_keeps_the_active_alias_and_does_not_create_target() {
        String target = "tongpin-threads-v0123456789abcdef0123456789abcdef";
        when(index.activeIndex()).thenReturn(Optional.of("tongpin-threads-vfedcba9876543210fedcba9876543210"));
        when(owner.scanAfter(0, 20)).thenReturn(List.of(tombstone));
        when(owner.scanAfter(7, 20)).thenReturn(List.of());
        when(index.count(target)).thenReturn(0L, 1L);
        when(index.read(target, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback(target)).isInstanceOf(IllegalStateException.class);

        verify(index, never()).create(anyString());
        verify(index, never()).switchAlias(anyString());
        verify(control, never()).finish(anyString(), anyString(), any(Runnable.class));
        verify(control).abort(anyString());
    }

    @Test
    void rollback_to_the_active_index_is_a_conflict_without_refreshing_or_switching() {
        String target = "tongpin-threads-v0123456789abcdef0123456789abcdef";
        when(index.activeIndex()).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.rollback(target))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("already active");

        verify(index, never()).count(target);
        verify(index, never()).refresh(target);
        verify(index, never()).switchAlias(target);
        verify(owner, never()).scanAfter(anyLong(), anyInt());
        verify(control).abort(anyString());
    }
}
