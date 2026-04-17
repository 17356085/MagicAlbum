package com.example.demo.threads.repo;

import com.example.demo.threads.dto.ThreadQueryView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ThreadRepository extends JpaRepository<com.example.demo.threads.entity.Thread, Long> {

    @Query(value = """
            SELECT
                t.id AS id,
                t.sectionId AS sectionId,
                s.name AS sectionName,
                t.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatar,
                t.title AS title,
                t.contentMd AS content,
                t.status AS status,
                t.createdAt AS createdAt,
                t.updatedAt AS updatedAt
            FROM Thread t
            LEFT JOIN com.example.demo.sections.entity.Section s ON s.id = t.sectionId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = t.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = t.authorId
            WHERE (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:sectionId IS NULL OR t.sectionId = :sectionId)
              AND t.status = 'NORMAL'
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t)
            FROM Thread t
            WHERE (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:sectionId IS NULL OR t.sectionId = :sectionId)
              AND t.status = 'NORMAL'
            """)
    Page<ThreadQueryView> searchNewestView(String q, Long sectionId, Pageable pageable);

    @Query(value = """
            SELECT
                t.id AS id,
                t.sectionId AS sectionId,
                s.name AS sectionName,
                t.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatar,
                t.title AS title,
                t.contentMd AS content,
                t.status AS status,
                t.createdAt AS createdAt,
                t.updatedAt AS updatedAt
            FROM Thread t
            LEFT JOIN com.example.demo.sections.entity.Section s ON s.id = t.sectionId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = t.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = t.authorId
            WHERE t.authorId = :authorId AND t.status = 'NORMAL'
            ORDER BY t.updatedAt DESC
            """,
            countQuery = "SELECT COUNT(t) FROM Thread t WHERE t.authorId = :authorId AND t.status = 'NORMAL'")
    Page<ThreadQueryView> findByAuthorNewestView(Long authorId, Pageable pageable);

    @Query(value = """
            SELECT
                t.id AS id,
                t.sectionId AS sectionId,
                s.name AS sectionName,
                t.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatar,
                t.title AS title,
                t.contentMd AS content,
                t.status AS status,
                t.createdAt AS createdAt,
                t.updatedAt AS updatedAt
            FROM Thread t
            LEFT JOIN com.example.demo.sections.entity.Section s ON s.id = t.sectionId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = t.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = t.authorId
            WHERE t.authorId = :authorId AND t.status = 'NORMAL'
            ORDER BY t.createdAt DESC
            """,
            countQuery = "SELECT COUNT(t) FROM Thread t WHERE t.authorId = :authorId AND t.status = 'NORMAL'")
    Page<ThreadQueryView> findByAuthorCreatedDescView(Long authorId, Pageable pageable);

    @Query(value = """
            SELECT
                t.id AS id,
                t.sectionId AS sectionId,
                s.name AS sectionName,
                t.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatar,
                t.title AS title,
                t.contentMd AS content,
                t.status AS status,
                t.createdAt AS createdAt,
                t.updatedAt AS updatedAt
            FROM Thread t
            LEFT JOIN com.example.demo.sections.entity.Section s ON s.id = t.sectionId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = t.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = t.authorId
            WHERE t.authorId = :authorId
              AND t.status = 'NORMAL'
              AND (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:sectionId IS NULL OR t.sectionId = :sectionId)
            ORDER BY t.updatedAt DESC
            """,
            countQuery = """
            SELECT COUNT(t)
            FROM Thread t
            WHERE t.authorId = :authorId
              AND t.status = 'NORMAL'
              AND (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:sectionId IS NULL OR t.sectionId = :sectionId)
            """)
    Page<ThreadQueryView> searchByAuthorUpdatedDescView(Long authorId, String q, Long sectionId, Pageable pageable);

    @Query(value = """
            SELECT
                t.id AS id,
                t.sectionId AS sectionId,
                s.name AS sectionName,
                t.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatar,
                t.title AS title,
                t.contentMd AS content,
                t.status AS status,
                t.createdAt AS createdAt,
                t.updatedAt AS updatedAt
            FROM Thread t
            LEFT JOIN com.example.demo.sections.entity.Section s ON s.id = t.sectionId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = t.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = t.authorId
            WHERE t.authorId = :authorId
              AND t.status = 'NORMAL'
              AND (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:sectionId IS NULL OR t.sectionId = :sectionId)
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t)
            FROM Thread t
            WHERE t.authorId = :authorId
              AND t.status = 'NORMAL'
              AND (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:sectionId IS NULL OR t.sectionId = :sectionId)
            """)
    Page<ThreadQueryView> searchByAuthorCreatedDescView(Long authorId, String q, Long sectionId, Pageable pageable);

    @Query("""
            SELECT
                t.id AS id,
                t.sectionId AS sectionId,
                s.name AS sectionName,
                t.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatar,
                t.title AS title,
                t.contentMd AS content,
                t.status AS status,
                t.createdAt AS createdAt,
                t.updatedAt AS updatedAt
            FROM Thread t
            LEFT JOIN com.example.demo.sections.entity.Section s ON s.id = t.sectionId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = t.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = t.authorId
            WHERE t.id = :id
            """)
    Optional<ThreadQueryView> findViewById(Long id);

    @Query("""
            SELECT
                t.id AS id,
                t.sectionId AS sectionId,
                s.name AS sectionName,
                t.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatar,
                t.title AS title,
                t.contentMd AS content,
                t.status AS status,
                t.createdAt AS createdAt,
                t.updatedAt AS updatedAt
            FROM Thread t
            LEFT JOIN com.example.demo.sections.entity.Section s ON s.id = t.sectionId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = t.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = t.authorId
            WHERE t.id IN :ids
            """)
    List<ThreadQueryView> findViewsByIdIn(Collection<Long> ids);
}
