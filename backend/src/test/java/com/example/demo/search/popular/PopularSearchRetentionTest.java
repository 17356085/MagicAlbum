package com.example.demo.search.popular;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PopularSearchRetentionTest {

    @Test
    void deletesOnlyExpiredRowsInOneBoundedBatch() {
        PopularSearchTermRepository repository = mock(PopularSearchTermRepository.class);
        PopularSearchRetention retention = new PopularSearchRetention(
                repository, Clock.fixed(Instant.parse("2026-11-24T04:05:06Z"), ZoneOffset.UTC));

        retention.purgeExpired();

        verify(repository).deleteExpiredBefore(
                java.time.LocalDate.of(2026, 11, 18), PopularSearchRetention.DELETE_BATCH_SIZE);
    }
}
