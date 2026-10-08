package com.example.demo.bookmarks.repo;

import com.example.demo.bookmarks.entity.ThreadBookmark;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ThreadBookmarkRepository extends JpaRepository<ThreadBookmark, Long> {

    boolean existsByUserIdAndThreadId(Long userId, Long threadId);

    Optional<ThreadBookmark> findByUserIdAndThreadId(Long userId, Long threadId);

    long deleteByUserIdAndThreadId(Long userId, Long threadId);

    @Query(value = """
            SELECT
                b.threadId AS threadId,
                t.title AS title,
                s.name AS sectionName,
                b.createdAt AS bookmarkedAt
            FROM ThreadBookmark b
            JOIN com.example.demo.threads.entity.Thread t ON t.id = b.threadId
            JOIN com.example.demo.sections.entity.Section s ON s.id = t.sectionId
            WHERE b.userId = :userId
              AND t.status = 'NORMAL'
            ORDER BY b.createdAt DESC, b.threadId DESC
            """,
            countQuery = """
            SELECT COUNT(b.id)
            FROM ThreadBookmark b
            JOIN com.example.demo.threads.entity.Thread t ON t.id = b.threadId
            JOIN com.example.demo.sections.entity.Section s ON s.id = t.sectionId
            WHERE b.userId = :userId
              AND t.status = 'NORMAL'
            """)
    Page<BookmarkItemProjection> findVisiblePageByUserId(@Param("userId") Long userId, Pageable pageable);

    interface BookmarkItemProjection {
        Long getThreadId();

        String getTitle();

        String getSectionName();

        Instant getBookmarkedAt();
    }
}
