package com.example.demo.posts.repo;

import com.example.demo.posts.entity.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    long countByPostId(Long postId);

    void deleteByPostIdAndUserId(Long postId, Long userId);
}
