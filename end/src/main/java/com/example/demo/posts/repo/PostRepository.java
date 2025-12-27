package com.example.demo.posts.repo;

import com.example.demo.posts.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Query("SELECT p FROM Post p WHERE p.threadId = :threadId AND p.status = 'NORMAL' ORDER BY p.createdAt ASC")
    Page<Post> findByThreadAsc(Long threadId, Pageable pageable);

    @Query("SELECT p FROM Post p WHERE p.id = :id AND p.status = 'NORMAL'")
    java.util.Optional<Post> findActiveById(Long id);

    @Query("SELECT p FROM Post p WHERE p.authorId = :authorId AND p.status = 'NORMAL' ORDER BY p.createdAt DESC")
    Page<Post> findByAuthorDesc(Long authorId, Pageable pageable);

    @Query("SELECT p FROM Post p WHERE p.authorId = :authorId AND p.status = 'NORMAL' ORDER BY p.updatedAt DESC")
    Page<Post> findByAuthorUpdatedDesc(Long authorId, Pageable pageable);

    @Query("SELECT p FROM Post p WHERE p.authorId = :authorId AND p.status = 'NORMAL' AND (:q IS NULL OR LOWER(p.contentMd) LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY p.createdAt DESC")
    Page<Post> searchByAuthorCreatedDesc(Long authorId, String q, Pageable pageable);

    @Query("SELECT p FROM Post p WHERE p.authorId = :authorId AND p.status = 'NORMAL' AND (:q IS NULL OR LOWER(p.contentMd) LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY p.updatedAt DESC")
    Page<Post> searchByAuthorUpdatedDesc(Long authorId, String q, Pageable pageable);
    // 分区筛选（通过线程表关联）
    @Query("SELECT p FROM Post p, com.example.demo.threads.entity.Thread t WHERE p.authorId = :authorId AND p.status = 'NORMAL' AND p.threadId = t.id AND (:sectionId IS NULL OR t.sectionId = :sectionId) ORDER BY p.createdAt DESC")
    Page<Post> findByAuthorCreatedDescWithSection(Long authorId, Long sectionId, Pageable pageable);

    @Query("SELECT p FROM Post p, com.example.demo.threads.entity.Thread t WHERE p.authorId = :authorId AND p.status = 'NORMAL' AND p.threadId = t.id AND (:sectionId IS NULL OR t.sectionId = :sectionId) ORDER BY p.updatedAt DESC")
    Page<Post> findByAuthorUpdatedDescWithSection(Long authorId, Long sectionId, Pageable pageable);

    @Query("SELECT p FROM Post p, com.example.demo.threads.entity.Thread t WHERE p.authorId = :authorId AND p.status = 'NORMAL' AND p.threadId = t.id AND (:sectionId IS NULL OR t.sectionId = :sectionId) AND (:q IS NULL OR LOWER(p.contentMd) LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY p.createdAt DESC")
    Page<Post> searchByAuthorCreatedDescWithSection(Long authorId, String q, Long sectionId, Pageable pageable);

    @Query("SELECT p FROM Post p, com.example.demo.threads.entity.Thread t WHERE p.authorId = :authorId AND p.status = 'NORMAL' AND p.threadId = t.id AND (:sectionId IS NULL OR t.sectionId = :sectionId) AND (:q IS NULL OR LOWER(p.contentMd) LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY p.updatedAt DESC")
    Page<Post> searchByAuthorUpdatedDescWithSection(Long authorId, String q, Long sectionId, Pageable pageable);
}