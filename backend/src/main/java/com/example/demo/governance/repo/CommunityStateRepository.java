package com.example.demo.governance.repo;

import com.example.demo.governance.entity.CommunityState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface CommunityStateRepository extends JpaRepository<CommunityState, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from CommunityState state where state.id = :id")
    Optional<CommunityState> findByIdForUpdate(@Param("id") Integer id);
}
