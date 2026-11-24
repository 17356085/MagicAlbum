package com.example.demo.sections.repo;

import com.example.demo.sections.entity.Section;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface SectionRepository extends JpaRepository<Section, Long> {

    /** Concurrent content writers may share the section while posting. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select section from Section section where section.id = :id")
    Optional<Section> findByIdForWriting(@Param("id") Long id);

    /** Governance edits require an exclusive section row lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select section from Section section where section.id = :id")
    Optional<Section> findByIdForUpdate(@Param("id") Long id);

    Optional<Section> findBySlug(String slug);

    Optional<Section> findBySlugAndIdNot(String slug, Long id);

    @Query("SELECT s FROM Section s WHERE (:q IS NULL OR LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(s.slug) LIKE LOWER(CONCAT('%', :q, '%'))) AND s.visible = TRUE ORDER BY s.createdAt DESC, s.id DESC")
    Page<Section> search(String q, Pageable pageable);
}
