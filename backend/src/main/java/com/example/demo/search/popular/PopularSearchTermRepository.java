package com.example.demo.search.popular;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@Repository
public class PopularSearchTermRepository {
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO popular_search_terms
                (search_type, account_id, keyword, normalized_keyword, search_day, created_at)
            SELECT ?, ?, ?, ?, ?, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM popular_search_terms
                WHERE search_type = ? AND account_id = ?
                  AND normalized_keyword = ? AND search_day = ?
            )
            """;
    private static final String FIND_POPULAR = """
            SELECT MIN(keyword) AS keyword, COUNT(DISTINCT account_id) AS searchers
            FROM popular_search_terms
            WHERE search_type = ? AND search_day BETWEEN ? AND ?
            GROUP BY normalized_keyword
            HAVING COUNT(DISTINCT account_id) >= ?
            ORDER BY searchers DESC, keyword ASC
            LIMIT ?
            """;
    private static final String FIND_EXPIRED_IDS = """
            SELECT id FROM popular_search_terms
            WHERE search_day < ?
            ORDER BY id
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public PopularSearchTermRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insertIfAbsent(long accountId, PopularSearchType type, String display,
                              String normalized, LocalDate searchDay, Instant recordedAt) {
        try {
            return jdbc.update(INSERT_IF_ABSENT,
                    type.value(), accountId, display, normalized, Date.valueOf(searchDay),
                    Timestamp.from(recordedAt),
                    type.value(), accountId, normalized, Date.valueOf(searchDay));
        } catch (DuplicateKeyException duplicate) {
            // A concurrent submission can pass NOT EXISTS; the unique key is the final guard.
            return 0;
        }
    }

    public List<String> findPopular(PopularSearchType type, LocalDate fromInclusive,
                                    LocalDate toInclusive, int limit, int minimumDistinctSearchers) {
        return jdbc.query(FIND_POPULAR,
                (resultSet, rowNumber) -> resultSet.getString("keyword"),
                type.value(), Date.valueOf(fromInclusive), Date.valueOf(toInclusive),
                minimumDistinctSearchers, limit);
    }

    public int deleteExpiredBefore(LocalDate beforeExclusive, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        List<Long> ids = jdbc.queryForList(FIND_EXPIRED_IDS, Long.class,
                Date.valueOf(beforeExclusive), batchSize);
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.update("DELETE FROM popular_search_terms WHERE id IN (" + placeholders + ")",
                ids.toArray());
    }
}
