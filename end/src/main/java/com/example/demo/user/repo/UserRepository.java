package com.example.demo.user.repo;

import com.example.demo.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    java.util.Optional<User> findByUsername(String username);
    java.util.Optional<User> findByEmail(String email);
    java.util.Optional<User> findByPhone(String phone);

    @Query("SELECT u FROM User u WHERE (:q IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(u.phone) LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY u.createdAt DESC")
    Page<User> search(String q, Pageable pageable);

    @Query("SELECT u FROM User u WHERE (:q IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(u.phone) LIKE LOWER(CONCAT('%', :q, '%')) OR EXISTS (SELECT 1 FROM com.example.demo.user.entity.UserProfile up WHERE up.userId = u.id AND LOWER(up.nickname) LIKE LOWER(CONCAT('%', :q, '%')))) ORDER BY u.createdAt DESC")
    Page<User> searchWithNickname(String q, Pageable pageable);
}