package com.example.demo.search.service;

import com.example.demo.search.port.ContentSearchIndex;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.example.demo.threads.event.dto.ContentEventMessage;
import com.example.demo.threads.service.ThreadSearchProjectionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContentSearchProjectionTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-19T00:00:00Z");
    private static final String UPSERT_ID = "0ab96974-4dce-4102-9ab5-b2e6e31775f4";
    private static final String HIDE_ID = "69c91441-abe6-4e6c-9f5b-8d5237609f28";

    private final ThreadSearchProjectionService owner = mock(ThreadSearchProjectionService.class);
    private final ContentSearchIndex index = mock(ContentSearchIndex.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ContentSearchProjection projection = new ContentSearchProjection(owner, index,
            new ContentSearchPipelineMetrics(meters));

    @Test
    void older_upsert_indexes_the_current_hidden_projection() {
        var hidden = new ThreadSearchDocument(7, 2, false, null, null, List.of(), 1L,
                OCCURRED_AT, HIDE_ID, "THREAD_HIDDEN");
        when(owner.read(7)).thenReturn(Optional.of(hidden));

        projection.apply(upsert(1));

        verify(index).put(ContentSearchIndex.ALIAS, hidden);
        assertThat(meters.summary("bluealbum.content.search.index.lag").count()).isEqualTo(1);
    }

    @Test
    void same_version_with_conflicting_event_is_rejected() {
        var hidden = new ThreadSearchDocument(7, 2, false, null, null, List.of(), 1L,
                OCCURRED_AT, HIDE_ID, "THREAD_HIDDEN");
        when(owner.read(7)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> projection.apply(upsert(2)))
                .isInstanceOf(InvalidContentEventException.class)
                .hasMessage("OWNER_VERSION_CONFLICT");
        verifyNoInteractions(index);
        assertThat(meters.summary("bluealbum.content.search.index.lag").count()).isZero();
    }

    @Test
    void a_projection_behind_the_event_is_retryable() {
        var current = new ThreadSearchDocument(7, 1, true, "title", "body", List.of(), 1L,
                OCCURRED_AT, UPSERT_ID, "THREAD_UPSERTED");
        when(owner.read(7)).thenReturn(Optional.of(current));
        var hide = new ContentEventMessage(HIDE_ID, 1, "THREAD", 7, 2, "THREAD_HIDDEN", OCCURRED_AT, null);

        assertThatThrownBy(() -> projection.apply(hide))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OWNER_BEHIND_EVENT");
        verifyNoInteractions(index);
    }

    private ContentEventMessage upsert(long version) {
        return new ContentEventMessage(UPSERT_ID, 1, "THREAD", 7, version, "THREAD_UPSERTED", OCCURRED_AT, null);
    }
}
