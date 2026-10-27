package com.example.demo.threads.service;

import com.example.demo.threads.dto.SectionThreadPreviewDto;
import com.example.demo.threads.repo.ThreadSectionPreviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThreadSectionPreviewServiceTest {
    @Mock
    private ThreadSectionPreviewRepository repository;

    private ThreadSectionPreviewService service;

    @BeforeEach
    void setUp() {
        service = new ThreadSectionPreviewService(repository);
    }

    @Test
    void emptyInputReturnsEmptyWithoutSql() {
        assertThat(service.read(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void invalidInputIsBadRequestBeforeSql() {
        List<List<Long>> invalidInputs = new ArrayList<>();
        invalidInputs.add(null);
        invalidInputs.add(List.of(0L));
        invalidInputs.add(List.of(-1L));
        invalidInputs.add(java.util.Arrays.asList(1L, null));
        invalidInputs.add(tooManyIds());
        for (List<Long> input : invalidInputs) {
            assertThatThrownBy(() -> service.read(input))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void deduplicatesPreservesInputOrderAndMapsLongIdsWithoutLoss() {
        Instant newest = Instant.parse("2026-01-03T00:00:00Z");
        String largeSectionId = "9007199254740993";
        String largeThreadId = "9007199254740995";
        when(repository.findLatestBySectionIds(anyCollection())).thenReturn(List.of(
                row(largeSectionId, 5L, largeThreadId, "newest", "![x](/cover.png)",
                        "9007199254740997", newest, "large-user", "large-nickname"),
                row("7", 1L, "70", "small", "text", "71", newest.minusSeconds(1), "user-7", null)
        ));

        List<SectionThreadPreviewDto> result = service.read(List.of(
                7L, 9_007_199_254_740_993L, 7L, 11L));

        assertThat(result).extracting(SectionThreadPreviewDto::sectionId)
                .containsExactly("7", largeSectionId, "11");
        assertThat(result.get(0).total()).isEqualTo(1L);
        assertThat(result.get(0).items()).hasSize(1);
        assertThat(result.get(0).items().get(0).authorNickname()).isNull();
        assertThat(result.get(1).items().get(0).id()).isEqualTo(largeThreadId);
        assertThat(result.get(1).items().get(0).authorId()).isEqualTo("9007199254740997");
        assertThat(result.get(1).items().get(0).coverUrl()).isEqualTo("/cover.png");
        assertThat(result.get(2).total()).isZero();
        assertThat(result.get(2).items()).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repository).findLatestBySectionIds(ids.capture());
        assertThat(ids.getValue()).containsExactly(7L, 9_007_199_254_740_993L, 11L);
    }

    @Test
    void serviceKeepsAtMostFourItemsEvenIfRepositoryReturnsMore() {
        List<ThreadSectionPreviewRepository.PreviewRow> rows = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            rows.add(row("10", 5L, String.valueOf(100 - index), "thread-" + index,
                    "body", "20", Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index),
                    "user", "nickname"));
        }
        when(repository.findLatestBySectionIds(anyCollection())).thenReturn(rows);

        SectionThreadPreviewDto result = service.read(List.of(10L)).get(0);

        assertThat(result.total()).isEqualTo(5L);
        assertThat(result.items()).hasSize(4);
    }

    @Test
    void coverExtractionMatchesMarkdownThenHtmlThenEmptyRules() {
        assertThat(ThreadSectionPreviewCoverExtractor.extract(
                "![cover](https://cdn.example/one.png \"title\")\n<img src='two.png'>"))
                .isEqualTo("https://cdn.example/one.png");
        assertThat(ThreadSectionPreviewCoverExtractor.extract(
                "![cover](https://cdn.example/nbsp.png\u00A0\"title\")"))
                .isEqualTo("https://cdn.example/nbsp.png");
        assertThat(ThreadSectionPreviewCoverExtractor.extract(
                "![cover](https://cdn.example/bom.png\uFEFF\"title\")"))
                .isEqualTo("https://cdn.example/bom.png");
        assertThat(ThreadSectionPreviewCoverExtractor.extract(
                "text <IMG class='cover' SRC=\"/uploads/two.png\">"))
                .isEqualTo("/uploads/two.png");
        assertThat(ThreadSectionPreviewCoverExtractor.extract("no image here")).isEmpty();
        assertThat(ThreadSectionPreviewCoverExtractor.extract(null)).isEmpty();
    }

    private static List<Long> tooManyIds() {
        return java.util.stream.LongStream.rangeClosed(1, ThreadSectionPreviewService.MAX_SECTION_IDS + 1)
                .boxed().toList();
    }

    private static ThreadSectionPreviewRepository.PreviewRow row(
            String sectionId,
            long total,
            String threadId,
            String title,
            String content,
            String authorId,
            Instant createdAt,
            String authorUsername,
            String authorNickname) {
        return new ThreadSectionPreviewRepository.PreviewRow(
                sectionId, total, threadId, title, content, authorId,
                authorUsername, authorNickname, createdAt);
    }
}
