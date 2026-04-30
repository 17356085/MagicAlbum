package com.example.demo.tags.repo;

import com.example.demo.tags.dto.TagStatsView;
import com.example.demo.tags.entity.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {
    @Query(value = """
            SELECT
                tg.id AS id,
                tg.name AS name,
                tg.type AS type,
                COUNT(DISTINCT tt.thread_id) AS threadCount
            FROM tags tg
            JOIN thread_tags tt ON tt.tag_id = tg.id
            JOIN threads th ON th.id = tt.thread_id
            WHERE th.status = 'NORMAL'
              AND (:sectionId IS NULL OR th.section_id = :sectionId)
            GROUP BY tg.id, tg.name, tg.type
            ORDER BY threadCount DESC, tg.name ASC
            """, nativeQuery = true)
    List<TagStatsView> findPopular(Long sectionId, Pageable pageable);

    @Query(value = """
            SELECT
                tg.id AS id,
                tg.name AS name,
                tg.type AS type,
                COUNT(DISTINCT tt.thread_id) AS threadCount
            FROM tags tg
            JOIN thread_tags tt ON tt.tag_id = tg.id
            JOIN threads th ON th.id = tt.thread_id
            WHERE th.status = 'NORMAL'
              AND tg.type = 'thread'
              AND (:sectionId IS NULL OR th.section_id = :sectionId)
              AND (:q IS NULL OR LOWER(tg.name) LIKE LOWER(CONCAT('%', :q, '%')))
            GROUP BY tg.id, tg.name, tg.type
            ORDER BY threadCount DESC, tg.name ASC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT tg.id)
            FROM tags tg
            JOIN thread_tags tt ON tt.tag_id = tg.id
            JOIN threads th ON th.id = tt.thread_id
            WHERE th.status = 'NORMAL'
              AND tg.type = 'thread'
              AND (:sectionId IS NULL OR th.section_id = :sectionId)
              AND (:q IS NULL OR LOWER(tg.name) LIKE LOWER(CONCAT('%', :q, '%')))
            """,
            nativeQuery = true)
    Page<TagStatsView> search(String q, Long sectionId, Pageable pageable);

    @Query("""
            SELECT t
            FROM Tag t
            WHERE LOWER(t.name) = LOWER(:name)
              AND t.type = :type
            """)
    Optional<Tag> findByNameAndType(String name, String type);
}
