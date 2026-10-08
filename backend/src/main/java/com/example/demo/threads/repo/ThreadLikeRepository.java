package com.example.demo.threads.repo;

import com.example.demo.threads.entity.ThreadLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThreadLikeRepository extends JpaRepository<ThreadLike, Long> {
    boolean existsByThreadIdAndUserId(Long threadId, Long userId);

    long countByThreadId(Long threadId);

    void deleteByThreadIdAndUserId(Long threadId, Long userId);
}
