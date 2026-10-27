package com.example.demo.threads.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Single-statement JDBC reader for bounded per-section thread previews. */
@Repository
public class ThreadSectionPreviewRepository {
    static final String PREVIEW_SQL = """
            WITH ranked_threads AS (
                SELECT
                    t.id,
                    t.section_id,
                    t.created_at,
                    ROW_NUMBER() OVER (
                        PARTITION BY t.section_id
                        ORDER BY t.created_at DESC, t.id DESC
                    ) AS preview_rank,
                    COUNT(*) OVER (PARTITION BY t.section_id) AS section_total
                FROM threads t
                WHERE t.status = 'NORMAL'
                  AND t.section_id IN (:sectionIds)
            )
            SELECT
                ranked.section_id AS section_id,
                ranked.section_total AS section_total,
                t.id AS thread_id,
                t.title AS title,
                t.content_md AS content_md,
                t.author_id AS author_id,
                t.created_at AS created_at,
                u.username AS author_username,
                up.nickname AS author_nickname
            FROM ranked_threads ranked
            JOIN threads t ON t.id = ranked.id
            LEFT JOIN users u ON u.id = t.author_id
            LEFT JOIN user_profiles up ON up.user_id = t.author_id
            WHERE ranked.preview_rank <= 4
            ORDER BY ranked.section_id, ranked.preview_rank
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ThreadSectionPreviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public List<PreviewRow> findLatestBySectionIds(Collection<Long> sectionIds) {
        if (sectionIds == null || sectionIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sectionIds", sectionIds);
        return jdbc.query(PREVIEW_SQL, parameters, (rs, rowNum) -> new PreviewRow(
                rs.getString("section_id"),
                rs.getLong("section_total"),
                rs.getString("thread_id"),
                rs.getString("title"),
                rs.getString("content_md"),
                rs.getString("author_id"),
                rs.getString("author_username"),
                rs.getString("author_nickname"),
                toInstant(rs.getTimestamp("created_at"))
        ));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record PreviewRow(
            String sectionId,
            long total,
            String threadId,
            String title,
            String content,
            String authorId,
            String authorUsername,
            String authorNickname,
            Instant createdAt
    ) {
    }
}
