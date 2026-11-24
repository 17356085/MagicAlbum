package com.example.demo.search.popular;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
public class PopularSearchRetention {
    static final int DELETE_BATCH_SIZE = 200;
    private static final Logger log = LoggerFactory.getLogger(PopularSearchRetention.class);

    private final PopularSearchTermRepository repository;
    private final Clock clock;

    @Autowired
    public PopularSearchRetention(PopularSearchTermRepository repository) {
        this(repository, Clock.systemUTC());
    }

    PopularSearchRetention(PopularSearchTermRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Keeps only the seven calendar days used by ranking. Each short run removes
     * at most 200 rows; a delayed first run avoids an H2/test startup query and
     * a missing table is non-fatal here.
     */
    @Scheduled(
            initialDelayString = "${app.search.popular.retention-initial-delay-ms:60000}",
            fixedDelayString = "${app.search.popular.retention-delay-ms:60000}")
    public void purgeExpired() {
        LocalDate cutoff = LocalDate.now(clock).minusDays(PopularSearchService.WINDOW_DAYS - 1L);
        try {
            repository.deleteExpiredBefore(cutoff, DELETE_BATCH_SIZE);
        } catch (DataAccessException failure) {
            // Retention is best effort and must not prevent an H2 or partially migrated
            // application from starting. The next bounded run retries the cleanup.
            log.debug("popular_search_retention_deferred", failure);
        }
    }
}
