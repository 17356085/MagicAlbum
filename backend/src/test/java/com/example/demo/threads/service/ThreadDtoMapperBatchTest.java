package com.example.demo.threads.service;

import com.example.demo.tags.service.TagService;
import com.example.demo.threads.dto.ThreadDto;
import com.example.demo.threads.dto.ThreadLikeCountView;
import com.example.demo.threads.dto.ThreadQueryView;
import com.example.demo.threads.dto.ThreadRankingView;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadLikeRepository;
import com.example.demo.threads.repo.ThreadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThreadDtoMapperBatchTest {

    @Test
    void entityBatchPreservesOrderFiltersMissingViewsAndUsesBatchEnrichment() {
        ThreadRepository threads = mock(ThreadRepository.class);
        ThreadLikeRepository likes = mock(ThreadLikeRepository.class);
        TagService tags = mock(TagService.class);
        ThreadDtoMapper mapper = new ThreadDtoMapper(threads, likes, tags);

        Thread first = entity(2L, "second");
        Thread second = entity(1L, "first");
        Thread missing = entity(3L, "missing");
        ThreadQueryView firstView = queryView(2L, "second");
        ThreadQueryView secondView = queryView(1L, "first");
        ThreadLikeCountView firstCount = likeCount(2L, 4L);
        ThreadLikeCountView secondCount = likeCount(1L, 0L);

        when(threads.findViewsByIdIn(List.of(2L, 1L, 3L))).thenReturn(List.of(secondView, firstView));
        when(likes.findLikeCountsByThreadIds(List.of(2L, 1L)))
                .thenReturn(List.of(firstCount, secondCount));
        when(tags.getNamesByThreadIds(List.of(2L, 1L)))
                .thenReturn(Map.of(2L, List.of("alpha", "zeta"), 1L, List.of("beta")));

        List<ThreadDto> result = mapper.toDtosFromEntities(List.of(first, second, missing));

        assertThat(result).extracting(ThreadDto::getId).containsExactly(2L, 1L);
        assertThat(result.get(0).getLikeCount()).isEqualTo(4L);
        assertThat(result.get(0).getTags()).containsExactly("alpha", "zeta");
        assertThat(result.get(1).getLikeCount()).isZero();
        assertThat(result.get(1).getTags()).containsExactly("beta");
        verify(likes).findLikeCountsByThreadIds(List.of(2L, 1L));
        verify(tags).getNamesByThreadIds(List.of(2L, 1L));
        verify(likes, never()).countByThreadId(any());
        verify(tags, never()).getNamesByThreadId(any());
    }

    @Test
    void entityBatchUsesCurrentProjectionStatusBeforeBatchEnrichment() {
        ThreadRepository threads = mock(ThreadRepository.class);
        ThreadLikeRepository likes = mock(ThreadLikeRepository.class);
        TagService tags = mock(TagService.class);
        ThreadDtoMapper mapper = new ThreadDtoMapper(threads, likes, tags);

        Thread visible = entity(2L, "visible");
        Thread hidden = entity(1L, "hidden");
        Thread deleted = entity(3L, "deleted");
        Thread unknown = entity(4L, "unknown");
        Thread missing = entity(5L, "missing");
        ThreadQueryView visibleView = queryView(2L, "visible", "NORMAL");
        ThreadQueryView hiddenView = statusOnlyView(1L, "HIDDEN");
        ThreadQueryView deletedView = statusOnlyView(3L, "DELETED");
        ThreadQueryView unknownView = statusOnlyView(4L, "UNKNOWN");
        ThreadLikeCountView visibleCount = likeCount(2L, 4L);

        when(threads.findViewsByIdIn(List.of(2L, 1L, 3L, 4L, 5L)))
                .thenReturn(List.of(deletedView, visibleView, unknownView, hiddenView));
        when(likes.findLikeCountsByThreadIds(anyCollection()))
                .thenReturn(List.of(visibleCount));
        when(tags.getNamesByThreadIds(anyCollection()))
                .thenReturn(Map.of(2L, List.of("visible-tag")));

        List<ThreadDto> result = mapper.toDtosFromEntities(List.of(visible, hidden, deleted, unknown, missing));

        assertThat(result).extracting(ThreadDto::getId).containsExactly(2L);
        assertThat(result.get(0).getLikeCount()).isEqualTo(4L);
        assertThat(result.get(0).getTags()).containsExactly("visible-tag");
        verify(likes).findLikeCountsByThreadIds(List.of(2L));
        verify(tags).getNamesByThreadIds(List.of(2L));
    }

    @Test
    void entityBatchSkipsEnrichmentWhenAllCurrentProjectionsAreNonpublic() {
        ThreadRepository threads = mock(ThreadRepository.class);
        ThreadLikeRepository likes = mock(ThreadLikeRepository.class);
        TagService tags = mock(TagService.class);
        ThreadDtoMapper mapper = new ThreadDtoMapper(threads, likes, tags);
        ThreadQueryView hiddenView = statusOnlyView(1L, "HIDDEN");
        ThreadQueryView deletedView = statusOnlyView(2L, "DELETED");
        ThreadQueryView unknownView = statusOnlyView(3L, "UNKNOWN");

        when(threads.findViewsByIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(hiddenView, deletedView, unknownView));

        assertThat(mapper.toDtosFromEntities(List.of(
                entity(1L, "hidden"), entity(2L, "deleted"), entity(3L, "unknown")))).isEmpty();
        verify(likes, never()).findLikeCountsByThreadIds(anyCollection());
        verify(tags, never()).getNamesByThreadIds(anyCollection());
    }

    @Test
    void rankingBatchKeepsProjectedCountsAndOnlyBatchesTags() {
        ThreadRepository threads = mock(ThreadRepository.class);
        ThreadLikeRepository likes = mock(ThreadLikeRepository.class);
        TagService tags = mock(TagService.class);
        ThreadDtoMapper mapper = new ThreadDtoMapper(threads, likes, tags);
        ThreadRankingView view = mock(ThreadRankingView.class);
        when(view.getId()).thenReturn(7L);
        when(view.getTitle()).thenReturn("ranked");
        when(view.getLikeCount()).thenReturn(9L);
        when(view.getReplyCount()).thenReturn(5L);
        when(view.getHotScore()).thenReturn(42L);
        when(tags.getNamesByThreadIds(List.of(7L))).thenReturn(Map.of(7L, List.of("alpha")));

        ThreadDto result = mapper.toRankingDtos(List.of(view)).get(0);

        assertThat(result.getLikeCount()).isEqualTo(9L);
        assertThat(result.getReplyCount()).isEqualTo(5L);
        assertThat(result.getHotScore()).isEqualTo(42L);
        assertThat(result.getTags()).containsExactly("alpha");
        verify(tags).getNamesByThreadIds(List.of(7L));
        verify(likes, never()).findLikeCountsByThreadIds(anyCollection());
        verify(likes, never()).countByThreadId(any());
    }

    @Test
    void countAndTagFallbacksAreIndependentAndNullCollaboratorsStaySafe() {
        ThreadRepository threads = mock(ThreadRepository.class);
        ThreadLikeRepository likes = mock(ThreadLikeRepository.class);
        TagService tags = mock(TagService.class);
        ThreadDtoMapper mapper = new ThreadDtoMapper(threads, likes, tags);
        ThreadQueryView view = queryView(8L, "fallback");
        when(likes.findLikeCountsByThreadIds(List.of(8L)))
                .thenThrow(new DataAccessResourceFailureException("like counts unavailable"));
        when(tags.getNamesByThreadIds(List.of(8L))).thenReturn(Map.of(8L, List.of("tag")));

        ThreadDto countFallback = mapper.toDtos(List.of(view)).get(0);
        assertThat(countFallback.getLikeCount()).isZero();
        assertThat(countFallback.getTags()).containsExactly("tag");

        ThreadLikeCountView count = likeCount(8L, 3L);
        doReturn(List.of(count)).when(likes).findLikeCountsByThreadIds(List.of(8L));
        when(tags.getNamesByThreadIds(List.of(8L)))
                .thenThrow(new DataAccessResourceFailureException("tags unavailable"));

        ThreadDto tagFallback = mapper.toDtos(List.of(view)).get(0);
        assertThat(tagFallback.getLikeCount()).isEqualTo(3L);
        assertThat(tagFallback.getTags()).isEmpty();

        ThreadDtoMapper nulls = new ThreadDtoMapper(threads, null, null);
        ThreadDto nullCollaborators = nulls.toDtos(List.of(view)).get(0);
        assertThat(nullCollaborators.getLikeCount()).isZero();
        assertThat(nullCollaborators.getTags()).isEmpty();
    }

    @Test
    void emptyBatchDoesNotQueryOptionalCollaborators() {
        ThreadRepository threads = mock(ThreadRepository.class);
        ThreadLikeRepository likes = mock(ThreadLikeRepository.class);
        TagService tags = mock(TagService.class);
        ThreadDtoMapper mapper = new ThreadDtoMapper(threads, likes, tags);

        assertThat(mapper.toDtos(List.of())).isEmpty();
        assertThat(mapper.toRankingDtos(List.of())).isEmpty();
        assertThat(mapper.toDtosFromEntities(List.of())).isEmpty();
        verifyNoBatchInteractions(likes, tags);
    }

    @Test
    void singleEntityStillUsesSingleViewEnrichmentPath() {
        ThreadRepository threads = mock(ThreadRepository.class);
        ThreadLikeRepository likes = mock(ThreadLikeRepository.class);
        TagService tags = mock(TagService.class);
        ThreadDtoMapper mapper = new ThreadDtoMapper(threads, likes, tags);
        ThreadQueryView view = queryView(11L, "single");
        ThreadQueryView unexpected = mock(ThreadQueryView.class);
        when(unexpected.getId()).thenReturn(99L);
        when(threads.findViewsByIdIn(List.of(11L))).thenReturn(List.of(unexpected, view));
        when(likes.countByThreadId(11L)).thenReturn(2L);
        when(tags.getNamesByThreadId(11L)).thenReturn(List.of("single-tag"));

        ThreadDto result = mapper.toDto(entity(11L, "single"));

        assertThat(result.getId()).isEqualTo(11L);
        assertThat(result.getLikeCount()).isEqualTo(2L);
        assertThat(result.getTags()).containsExactly("single-tag");
        verify(threads).findViewsByIdIn(List.of(11L));
        verify(likes).countByThreadId(11L);
        verify(tags).getNamesByThreadId(11L);
        verify(likes, never()).findLikeCountsByThreadIds(anyCollection());
        verify(tags, never()).getNamesByThreadIds(anyCollection());
    }

    private static void verifyNoBatchInteractions(ThreadLikeRepository likes, TagService tags) {
        verify(likes, never()).findLikeCountsByThreadIds(anyCollection());
        verify(likes, never()).countByThreadId(any());
        verify(tags, never()).getNamesByThreadIds(anyCollection());
        verify(tags, never()).getNamesByThreadId(any());
    }

    private static Thread entity(Long id, String title) {
        Thread thread = new Thread();
        thread.setId(id);
        thread.setSectionId(1L);
        thread.setAuthorId(2L);
        thread.setTitle(title);
        thread.setContentMd("content");
        thread.setStatus("NORMAL");
        return thread;
    }

    private static ThreadQueryView queryView(Long id, String title) {
        return queryView(id, title, "NORMAL");
    }

    private static ThreadQueryView queryView(Long id, String title, String status) {
        ThreadQueryView view = mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(id);
        when(view.getSectionId()).thenReturn(1L);
        when(view.getAuthorId()).thenReturn(2L);
        when(view.getTitle()).thenReturn(title);
        when(view.getContent()).thenReturn("content");
        when(view.getStatus()).thenReturn(status);
        return view;
    }

    private static ThreadQueryView statusOnlyView(Long id, String status) {
        ThreadQueryView view = mock(ThreadQueryView.class);
        when(view.getId()).thenReturn(id);
        when(view.getStatus()).thenReturn(status);
        return view;
    }

    private static ThreadLikeCountView likeCount(Long id, Long count) {
        ThreadLikeCountView view = mock(ThreadLikeCountView.class);
        when(view.getThreadId()).thenReturn(id);
        when(view.getLikeCount()).thenReturn(count);
        return view;
    }
}
