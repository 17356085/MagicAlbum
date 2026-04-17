package com.example.demo.posts.repo;

import com.example.demo.posts.dto.PostQueryView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PostRepository extends JpaRepository<com.example.demo.posts.entity.Post, Long> {

    @Query("SELECT p FROM Post p WHERE p.id = :id AND p.status = 'NORMAL'")
    java.util.Optional<com.example.demo.posts.entity.Post> findActiveById(Long id);

    @Query(value = """
            SELECT
                p.id AS id,
                p.threadId AS threadId,
                th.title AS threadTitle,
                p.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatarUrl,
                p.contentMd AS content,
                p.replyToPostId AS replyToPostId,
                parent.authorId AS parentAuthorId,
                pu.username AS parentAuthorUsername,
                pup.nickname AS parentAuthorNickname,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt
            FROM Post p
            LEFT JOIN com.example.demo.threads.entity.Thread th ON th.id = p.threadId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = p.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = p.authorId
            LEFT JOIN Post parent ON parent.id = p.replyToPostId
            LEFT JOIN com.example.demo.user.entity.User pu ON pu.id = parent.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile pup ON pup.userId = parent.authorId
            WHERE p.threadId = :threadId AND p.status = 'NORMAL'
            ORDER BY p.createdAt ASC
            """,
            countQuery = "SELECT COUNT(p) FROM Post p WHERE p.threadId = :threadId AND p.status = 'NORMAL'")
    Page<PostQueryView> findByThreadAscView(Long threadId, Pageable pageable);

    @Query(value = """
            SELECT
                p.id AS id,
                p.threadId AS threadId,
                th.title AS threadTitle,
                p.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatarUrl,
                p.contentMd AS content,
                p.replyToPostId AS replyToPostId,
                parent.authorId AS parentAuthorId,
                pu.username AS parentAuthorUsername,
                pup.nickname AS parentAuthorNickname,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt
            FROM Post p
            LEFT JOIN com.example.demo.threads.entity.Thread th ON th.id = p.threadId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = p.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = p.authorId
            LEFT JOIN Post parent ON parent.id = p.replyToPostId
            LEFT JOIN com.example.demo.user.entity.User pu ON pu.id = parent.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile pup ON pup.userId = parent.authorId
            WHERE p.authorId = :authorId
              AND p.status = 'NORMAL'
              AND p.threadId = th.id
              AND (:sectionId IS NULL OR th.sectionId = :sectionId)
              AND (:q IS NULL OR LOWER(p.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY p.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p, com.example.demo.threads.entity.Thread th
            WHERE p.authorId = :authorId
              AND p.status = 'NORMAL'
              AND p.threadId = th.id
              AND (:sectionId IS NULL OR th.sectionId = :sectionId)
              AND (:q IS NULL OR LOWER(p.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<PostQueryView> searchByAuthorCreatedDescWithSectionView(Long authorId, String q, Long sectionId, Pageable pageable);

    @Query(value = """
            SELECT
                p.id AS id,
                p.threadId AS threadId,
                th.title AS threadTitle,
                p.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatarUrl,
                p.contentMd AS content,
                p.replyToPostId AS replyToPostId,
                parent.authorId AS parentAuthorId,
                pu.username AS parentAuthorUsername,
                pup.nickname AS parentAuthorNickname,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt
            FROM Post p
            LEFT JOIN com.example.demo.threads.entity.Thread th ON th.id = p.threadId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = p.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = p.authorId
            LEFT JOIN Post parent ON parent.id = p.replyToPostId
            LEFT JOIN com.example.demo.user.entity.User pu ON pu.id = parent.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile pup ON pup.userId = parent.authorId
            WHERE p.authorId = :authorId
              AND p.status = 'NORMAL'
              AND p.threadId = th.id
              AND (:sectionId IS NULL OR th.sectionId = :sectionId)
              AND (:q IS NULL OR LOWER(p.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY p.updatedAt DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p, com.example.demo.threads.entity.Thread th
            WHERE p.authorId = :authorId
              AND p.status = 'NORMAL'
              AND p.threadId = th.id
              AND (:sectionId IS NULL OR th.sectionId = :sectionId)
              AND (:q IS NULL OR LOWER(p.contentMd) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<PostQueryView> searchByAuthorUpdatedDescWithSectionView(Long authorId, String q, Long sectionId, Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.threadId AS threadId,
                th.title AS threadTitle,
                p.authorId AS authorId,
                u.username AS authorUsername,
                up.nickname AS authorNickname,
                up.avatarUrl AS authorAvatarUrl,
                p.contentMd AS content,
                p.replyToPostId AS replyToPostId,
                parent.authorId AS parentAuthorId,
                pu.username AS parentAuthorUsername,
                pup.nickname AS parentAuthorNickname,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt
            FROM Post p
            LEFT JOIN com.example.demo.threads.entity.Thread th ON th.id = p.threadId
            LEFT JOIN com.example.demo.user.entity.User u ON u.id = p.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile up ON up.userId = p.authorId
            LEFT JOIN Post parent ON parent.id = p.replyToPostId
            LEFT JOIN com.example.demo.user.entity.User pu ON pu.id = parent.authorId
            LEFT JOIN com.example.demo.user.entity.UserProfile pup ON pup.userId = parent.authorId
            WHERE p.id = :id
            """)
    Optional<PostQueryView> findViewById(Long id);
}
