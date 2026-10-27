package com.example.demo.threads.repo;

import com.example.demo.threads.dto.ThreadLikeCountView;
import com.example.demo.threads.entity.ThreadLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ThreadLikeRepository extends JpaRepository<ThreadLike, Long> {
    boolean existsByThreadIdAndUserId(Long threadId, Long userId);

    long countByThreadId(Long threadId);

    void deleteByThreadIdAndUserId(Long threadId, Long userId);

    @Query("SELECT tl.threadId AS threadId, COUNT(tl) AS likeCount "
            + "FROM ThreadLike tl WHERE tl.threadId IN :threadIds GROUP BY tl.threadId")
    List<ThreadLikeCountView> findLikeCountsByThreadIds(@Param("threadIds") Collection<Long> threadIds);
}
