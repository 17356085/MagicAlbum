package com.example.demo.social.repo;

import com.example.demo.social.entity.UserFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {
    boolean existsByFollowerIdAndFollowingId(Long followerId, Long followingId);

    long countByFollowerId(Long followerId);

    long countByFollowingId(Long followingId);

    void deleteByFollowerIdAndFollowingId(Long followerId, Long followingId);

    @Query("SELECT uf.followingId FROM UserFollow uf WHERE uf.followerId = :followerId ORDER BY uf.createdAt DESC")
    Page<Long> findFollowingIds(Long followerId, Pageable pageable);

    @Query("SELECT uf.followerId FROM UserFollow uf WHERE uf.followingId = :followingId ORDER BY uf.createdAt DESC")
    Page<Long> findFollowerIds(Long followingId, Pageable pageable);

    @Query("""
            SELECT outgoing.followingId FROM UserFollow outgoing
            WHERE outgoing.followerId = :userId AND outgoing.followingId NOT IN :excluded
              AND EXISTS (SELECT incoming.id FROM UserFollow incoming
                          WHERE incoming.followerId = outgoing.followingId AND incoming.followingId = :userId)
            ORDER BY outgoing.createdAt DESC, outgoing.followingId
            """)
    Page<Long> findMutualIds(Long userId, java.util.Collection<Long> excluded, Pageable pageable);
}
