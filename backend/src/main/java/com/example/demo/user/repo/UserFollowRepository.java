package com.example.demo.user.repo;

import com.example.demo.user.entity.UserFollow;
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
}
