package com.example.demo.search.popular;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PopularSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T04:05:06Z");
    private final PopularSearchTermRepository repository = mock(PopularSearchTermRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PopularSearchService service = new PopularSearchService(repository, clock);

    @Test
    void recordsOnlyNormalizedValidTermsForTheAuthenticatedAccountAndUtcDay() {
        service.record(42L, "threads", "  Spring   Boot 3 ");

        verify(repository).insertIfAbsent(
                eq(42L),
                eq(PopularSearchType.THREADS),
                eq("Spring Boot 3"),
                eq("spring boot 3"),
                eq(LocalDate.of(2026, 11, 24)),
                eq(NOW));
    }

    @Test
    void invalidPersonalTermsAreNoOps() {
        service.record(42L, "users", "alice@example.com");

        verifyNoInteractions(repository);
    }

    @Test
    void popularRequiresTheSevenCalendarDayWindowAndMinimumFiveDistinctSearchers() {
        when(repository.findPopular(
                PopularSearchType.USERS,
                LocalDate.of(2026, 11, 18),
                LocalDate.of(2026, 11, 24),
                PopularSearchService.MAX_RESULTS,
                PopularSearchService.MIN_DISTINCT_SEARCHERS))
                .thenReturn(List.of("spring", "java"));

        assertThat(service.popular("users")).containsExactly("spring", "java");
        verify(repository).findPopular(
                PopularSearchType.USERS,
                LocalDate.of(2026, 11, 18),
                LocalDate.of(2026, 11, 24),
                PopularSearchService.MAX_RESULTS,
                PopularSearchService.MIN_DISTINCT_SEARCHERS);
    }

    @Test
    void invalidTypeIsRejectedBeforeReadingOrWritingData() {
        assertThatThrownBy(() -> service.popular("posts"))
                .isInstanceOf(PopularSearchType.InvalidPopularSearchTypeException.class);
        assertThatThrownBy(() -> service.record(42L, "posts", "spring"))
                .isInstanceOf(PopularSearchType.InvalidPopularSearchTypeException.class);
        verifyNoInteractions(repository);
    }
}
