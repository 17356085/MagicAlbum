package com.example.demo.threads.maintenance.repo;

import com.example.demo.threads.maintenance.entity.ContentSearchControl;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContentSearchControlRepository extends JpaRepository<ContentSearchControl, Long> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT c FROM ContentSearchControl c WHERE c.id = :id")
    Optional<ContentSearchControl> findByIdForRead(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ContentSearchControl c WHERE c.id = :id")
    Optional<ContentSearchControl> findByIdForUpdate(@Param("id") Long id);
}
