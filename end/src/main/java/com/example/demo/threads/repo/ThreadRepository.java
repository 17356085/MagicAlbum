package com.example.demo.threads.repo;

import com.example.demo.threads.entity.Thread;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ThreadRepository extends JpaRepository<Thread, Long> {

    @Query("SELECT t FROM Thread t WHERE (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.contentMd) LIKE LOWER(CONCAT('%', :q, '%'))) AND (:sectionId IS NULL OR t.sectionId = :sectionId) AND t.status = 'NORMAL' ORDER BY t.createdAt DESC")
    Page<Thread> searchNewest(String q, Long sectionId, Pageable pageable);

    @Query("SELECT t FROM Thread t WHERE t.authorId = :authorId AND t.status = 'NORMAL' ORDER BY t.updatedAt DESC")
    Page<Thread> findByAuthorNewest(Long authorId, Pageable pageable);

    @Query("SELECT t FROM Thread t WHERE t.authorId = :authorId AND t.status = 'NORMAL' ORDER BY t.createdAt DESC")
    Page<Thread> findByAuthorCreatedDesc(Long authorId, Pageable pageable);

    @Query("SELECT t FROM Thread t WHERE t.authorId = :authorId AND t.status = 'NORMAL' AND (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.contentMd) LIKE LOWER(CONCAT('%', :q, '%'))) AND (:sectionId IS NULL OR t.sectionId = :sectionId) ORDER BY t.updatedAt DESC")
    Page<Thread> searchByAuthorUpdatedDesc(Long authorId, String q, Long sectionId, Pageable pageable);

    @Query("SELECT t FROM Thread t WHERE t.authorId = :authorId AND t.status = 'NORMAL' AND (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.contentMd) LIKE LOWER(CONCAT('%', :q, '%'))) AND (:sectionId IS NULL OR t.sectionId = :sectionId) ORDER BY t.createdAt DESC")
    Page<Thread> searchByAuthorCreatedDesc(Long authorId, String q, Long sectionId, Pageable pageable);
}