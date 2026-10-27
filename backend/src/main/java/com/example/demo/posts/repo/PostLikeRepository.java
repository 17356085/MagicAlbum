package com.example.demo.posts.repo;

import com.example.demo.posts.dto.PostLikeCountView;
import com.example.demo.posts.entity.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    long countByPostId(Long postId);

    void deleteByPostIdAndUserId(Long postId, Long userId);

    @Query("SELECT pl.postId AS postId, COUNT(pl) AS likeCount "
            + "FROM PostLike pl WHERE pl.postId IN :postIds GROUP BY pl.postId")
    List<PostLikeCountView> findLikeCountsByPostIds(@Param("postIds") Collection<Long> postIds);

    @Query("SELECT pl.postId FROM PostLike pl "
            + "WHERE pl.userId = :userId AND pl.postId IN :postIds")
    List<Long> findPostIdsLikedByUser(@Param("userId") Long userId,
                                      @Param("postIds") Collection<Long> postIds);
}
