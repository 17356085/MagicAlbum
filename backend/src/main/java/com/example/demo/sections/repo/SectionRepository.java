package com.example.demo.sections.repo;

import com.example.demo.sections.entity.Section;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SectionRepository extends JpaRepository<Section, Long> {

    @Query("SELECT s FROM Section s WHERE (:q IS NULL OR LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(s.slug) LIKE LOWER(CONCAT('%', :q, '%'))) AND s.visible = TRUE ORDER BY s.createdAt DESC")
    Page<Section> search(String q, Pageable pageable);
}