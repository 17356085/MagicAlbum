package com.example.demo.governance.repo;

import com.example.demo.sections.entity.Section;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

/**
 * Governance-only access to the existing section entity. Keeping the lock
 * query here avoids changing the sections module's public repository contract.
 */
public interface SectionGovernanceRepository extends JpaRepository<Section, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select section from Section section where section.id = :id")
    Optional<Section> findByIdForUpdate(@Param("id") Long id);

    Optional<Section> findBySlug(String slug);

    Optional<Section> findBySlugAndIdNot(String slug, Long id);
}
