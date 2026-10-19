package com.example.demo.threads.service;

import com.example.demo.tags.service.TagService;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.dto.ThreadSearchDocument;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.event.entity.ContentEventOutbox;
import com.example.demo.threads.event.entity.ContentEventType;
import com.example.demo.threads.event.repo.ContentEventOutboxRepository;
import com.example.demo.threads.repo.ThreadLikeRepository;
import com.example.demo.threads.repo.ThreadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThreadSearchProjectionServiceTest {
    @Mock ThreadRepository threadRepository;
    @Mock ContentEventOutboxRepository eventRepository;
    @Mock ThreadLikeRepository threadLikeRepository;
    @Mock TagService tagService;

    private ThreadSearchProjectionService service;

    @BeforeEach
    void setUp() {
        service = new ThreadSearchProjectionService(
                threadRepository, eventRepository, threadLikeRepository, tagService);
    }

    @Test
    void hiddenAndDeletedRowsBecomePermanentEmptyTombstones() {
        Thread hidden = thread(7L, 0L, "private title", "private body", "HIDDEN");
        when(threadRepository.findById(7L)).thenReturn(Optional.of(hidden));

        ThreadSearchDocument document = service.read(7L).orElseThrow();

        assertThat(document.visible()).isFalse();
        assertThat(document.title()).isEmpty();
        assertThat(document.content()).isEmpty();
        assertThat(document.tags()).isEmpty();
        assertThat(document.eventId()).isEqualTo("baseline:7:0");
        assertThat(document.eventType()).isEqualTo("THREAD_HIDDEN");

        Thread deleted = thread(12L, 0L, "deleted title", "deleted body", "DELETED");
        when(threadRepository.findById(12L)).thenReturn(Optional.of(deleted));
        ThreadSearchDocument deletedDocument = service.read(12L).orElseThrow();
        assertThat(deletedDocument.visible()).isFalse();
        assertThat(deletedDocument.title()).isEmpty();
        assertThat(deletedDocument.content()).isEmpty();
        assertThat(deletedDocument.tags()).isEmpty();
        assertThat(deletedDocument.eventType()).isEqualTo("THREAD_DELETED");
    }

    @Test
    void v0UsesBaselineProvenanceAndLatestVersionUsesRealOutboxProvenance() {
        Thread legacy = thread(8L, 0L, "Legacy", "Body", "NORMAL");
        when(threadRepository.findById(8L)).thenReturn(Optional.of(legacy));
        when(tagService.getNamesByThreadId(8L)).thenReturn(List.of("Java", "JAVA", " Search "));

        ThreadSearchDocument baseline = service.read(8L).orElseThrow();
        assertThat(baseline.eventId()).isEqualTo("baseline:8:0");
        assertThat(baseline.eventType()).isEqualTo("THREAD_UPSERTED");
        assertThat(baseline.tags()).containsExactly("java", "search");

        Thread current = thread(8L, 3L, "Current", "Updated", "NORMAL");
        ContentEventOutbox event = new ContentEventOutbox();
        event.setEventId("event-8-v3");
        event.setEventType(ContentEventType.THREAD_UPSERTED);
        when(threadRepository.findById(8L)).thenReturn(Optional.of(current));
        when(eventRepository.findByAggregateIdAndAggregateVersion(8L, 3L))
                .thenReturn(Optional.of(event));

        ThreadSearchDocument latest = service.read(8L).orElseThrow();
        assertThat(latest.eventId()).isEqualTo("event-8-v3");
        assertThat(latest.eventType()).isEqualTo("THREAD_UPSERTED");
    }

    @Test
    void latestVersionWithoutOutboxProvenanceFailsClosed() {
        Thread current = thread(9L, 2L, "Current", "Updated", "NORMAL");
        when(threadRepository.findById(9L)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.read(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing content event");
    }

    @Test
    void staleVisibleResultIsRejectedBeforeQueryProjectionMapping() {
        Thread current = thread(10L, 3L, "Current", "Updated", "NORMAL");
        when(threadRepository.findByIdForSearchProjection(10L)).thenReturn(Optional.of(current));

        assertThat(service.visibleResult(10L, 2L)).isEmpty();
        verify(threadRepository, never()).findViewById(any());
    }

    @Test
    void databaseSearchTrimsEmptyInputsAndUsesDirectJpaProjection() {
        ThreadQueryView view = org.mockito.Mockito.mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(11L);
        when(view.getTitle()).thenReturn("Title");
        when(view.getContent()).thenReturn("Content");
        when(view.getStatus()).thenReturn("NORMAL");
        when(threadRepository.searchNewestView(eq("blue"), eq("java"), eq(4L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(1, 10), 1));
        when(tagService.getNamesByThreadId(11L)).thenReturn(List.of());

        var result = service.databaseSearch("  blue ", " java ", 4L, 2, 20);

        assertThat(result.getContent()).singleElement().extracting(ThreadDto::getId).isEqualTo(11L);
        verify(threadRepository).searchNewestView(eq("blue"), eq("java"), eq(4L), eq(PageRequest.of(1, 10)));
    }

    private Thread thread(Long id, long version, String title, String content, String status) {
        Thread thread = new Thread();
        thread.setId(id);
        thread.setSectionId(4L);
        thread.setAuthorId(5L);
        thread.setTitle(title);
        thread.setContentMd(content);
        thread.setStatus(status);
        thread.setContentVersion(version);
        thread.setCreatedAt(Instant.parse("2026-10-19T00:00:00Z"));
        return thread;
    }
}
