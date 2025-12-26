package com.example.demo.user.repo;

import com.example.demo.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    UserProfile findByUserId(Long userId);
}