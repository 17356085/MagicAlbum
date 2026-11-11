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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    void staleVisibleResultsAreRejectedBeforeQueryProjectionMapping() {
        Thread current = thread(10L, 3L, "Current", "Updated", "NORMAL");
        LinkedHashMap<Long, Long> expected = new LinkedHashMap<>();
        expected.put(10L, 2L);
        when(threadRepository.findAllByIdForSearchProjection(expected.keySet()))
                .thenReturn(List.of(current));

        assertThat(service.visibleResults(expected)).isEmpty();
        verify(threadRepository, never()).findViewsByIdIn(any());
    }

    @Test
    void hiddenVisibleResultIsRejectedBeforeQueryProjectionMapping() {
        Thread hidden = thread(10L, 3L, "Hidden", "Private", "HIDDEN");
        LinkedHashMap<Long, Long> expected = new LinkedHashMap<>();
        expected.put(10L, 3L);
        when(threadRepository.findAllByIdForSearchProjection(expected.keySet()))
                .thenReturn(List.of(hidden));

        assertThat(service.visibleResults(expected)).isEmpty();
        verify(threadRepository, never()).findViewsByIdIn(any());
    }

    @Test
    void visibleResultsBatchMapsInIndexOrderAndEnrichesOnce() {
        Thread first = thread(10L, 3L, "First", "Body 1", "NORMAL");
        Thread second = thread(7L, 4L, "Second", "Body 2", "NORMAL");
        LinkedHashMap<Long, Long> expected = new LinkedHashMap<>();
        expected.put(10L, 3L);
        expected.put(7L, 4L);
        when(threadRepository.findAllByIdForSearchProjection(expected.keySet()))
                .thenReturn(List.of(second, first));

        ThreadQueryView firstView = view(10L, "First", "Body 1");
        ThreadQueryView secondView = view(7L, "Second", "Body 2");
        when(threadRepository.findViewsByIdIn(List.of(10L, 7L)))
                .thenReturn(List.of(secondView, firstView));
        var firstLikeCount = likeCount(10L, 4L);
        var secondLikeCount = likeCount(7L, 2L);
        when(threadLikeRepository.findLikeCountsByThreadIds(List.of(10L, 7L)))
                .thenReturn(List.of(firstLikeCount, secondLikeCount));
        when(tagService.getNamesByThreadIds(List.of(10L, 7L)))
                .thenReturn(Map.of(10L, List.of("first"), 7L, List.of("second")));

        List<ThreadDto> result = service.visibleResults(expected).orElseThrow();

        assertThat(result).extracting(ThreadDto::getId).containsExactly(10L, 7L);
        assertThat(result).extracting(ThreadDto::getLikeCount).containsExactly(4L, 2L);
        assertThat(result).extracting(ThreadDto::getTags)
                .containsExactly(List.of("first"), List.of("second"));
        verify(threadRepository).findViewsByIdIn(List.of(10L, 7L));
        verify(threadLikeRepository).findLikeCountsByThreadIds(List.of(10L, 7L));
        verify(tagService).getNamesByThreadIds(List.of(10L, 7L));
    }

    @Test
    void incompleteQueryViewsCauseTheWholeVisibleBatchToBeRejected() {
        Thread first = thread(10L, 3L, "First", "Body 1", "NORMAL");
        Thread second = thread(7L, 4L, "Second", "Body 2", "NORMAL");
        LinkedHashMap<Long, Long> expected = new LinkedHashMap<>();
        expected.put(10L, 3L);
        expected.put(7L, 4L);
        when(threadRepository.findAllByIdForSearchProjection(expected.keySet()))
                .thenReturn(List.of(second, first));
        ThreadQueryView firstView = view(10L, "First", "Body 1");
        when(threadRepository.findViewsByIdIn(List.of(10L, 7L)))
                .thenReturn(List.of(firstView));
        var firstLikeCount = likeCount(10L, 1L);
        when(threadLikeRepository.findLikeCountsByThreadIds(List.of(10L)))
                .thenReturn(List.of(firstLikeCount));
        when(tagService.getNamesByThreadIds(List.of(10L)))
                .thenReturn(Map.of(10L, List.of("first")));

        assertThat(service.visibleResults(expected)).isEmpty();
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
        when(tagService.getNamesByThreadIds(List.of(11L)))
                .thenReturn(Map.of(11L, List.of("java")));

        var result = service.databaseSearch("  blue ", " java ", 4L, 2, 20);

        assertThat(result.getContent()).singleElement().extracting(ThreadDto::getId).isEqualTo(11L);
        assertThat(result.getContent()).singleElement().extracting(ThreadDto::getTags)
                .isEqualTo(List.of("java"));
        verify(threadRepository).searchNewestView(eq("blue"), eq("java"), eq(4L), eq(PageRequest.of(1, 10)));
        verify(tagService).getNamesByThreadIds(List.of(11L));
        verify(tagService, never()).getNamesByThreadId(11L);
        verify(threadLikeRepository).findLikeCountsByThreadIds(List.of(11L));
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

    private ThreadQueryView view(Long id, String title, String content) {
        ThreadQueryView view = org.mockito.Mockito.mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(id);
        when(view.getSectionId()).thenReturn(4L);
        when(view.getAuthorId()).thenReturn(5L);
        when(view.getTitle()).thenReturn(title);
        when(view.getContent()).thenReturn(content);
        when(view.getStatus()).thenReturn("NORMAL");
        when(view.getCreatedAt()).thenReturn(Instant.parse("2026-10-19T00:00:00Z"));
        return view;
    }

    private com.example.demo.threads.dto.ThreadLikeCountView likeCount(Long threadId, Long count) {
        com.example.demo.threads.dto.ThreadLikeCountView view =
                org.mockito.Mockito.mock(com.example.demo.threads.dto.ThreadLikeCountView.class);
        when(view.getThreadId()).thenReturn(threadId);
        when(view.getLikeCount()).thenReturn(count);
        return view;
    }
}
