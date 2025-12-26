package com.example.demo.posts.repo;

import com.example.demo.posts.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Query("SELECT p FROM Post p WHERE p.threadId = :threadId AND p.status = 'NORMAL' ORDER BY p.createdAt ASC")
    Page<Post> findByThreadAsc(Long threadId, Pageable pageable);

    @Query("SELECT p FROM Post p WHERE p.id = :id AND p.status = 'NORMAL'")
    java.util.Optional<Post> findActiveById(Long id);

    @Query("SELECT p FROM Post p WHERE p.authorId = :authorId AND p.status = 'NORMAL' ORDER BY p.createdAt DESC")
    Page<Post> findByAuthorDesc(Long authorId, Pageable pageable);
}