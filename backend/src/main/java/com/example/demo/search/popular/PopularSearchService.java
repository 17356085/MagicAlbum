package com.example.demo.search.popular;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
public class PopularSearchService {
    public static final int MAX_RESULTS = 10;
    public static final int MIN_DISTINCT_SEARCHERS = 5;
    public static final int WINDOW_DAYS = 7;

    private final PopularSearchTermRepository repository;
    private final Clock clock;

    @Autowired
    public PopularSearchService(PopularSearchTermRepository repository) {
        this(repository, Clock.systemUTC());
    }

    PopularSearchService(PopularSearchTermRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<String> popular(String rawType) {
        PopularSearchType type = PopularSearchType.parse(rawType);
        LocalDate today = LocalDate.now(clock);
        return repository.findPopular(type, today.minusDays(WINDOW_DAYS - 1L), today,
                MAX_RESULTS, MIN_DISTINCT_SEARCHERS)
                .stream()
                .limit(MAX_RESULTS)
                .toList();
    }

    public void record(Long accountId, String rawType, String rawQuery) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("authenticated account is required");
        }
        PopularSearchType type = PopularSearchType.parse(rawType);
        PopularSearchQueryPolicy.normalize(rawQuery).ifPresent(query -> {
            LocalDate today = LocalDate.now(clock);
            repository.insertIfAbsent(accountId, type, query.display(), query.normalized(), today,
                    clock.instant());
        });
    }
}
