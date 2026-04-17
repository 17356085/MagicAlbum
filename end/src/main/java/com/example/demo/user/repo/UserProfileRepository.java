package com.example.demo.user.repo;

import com.example.demo.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    UserProfile findByUserId(Long userId);
    List<UserProfile> findByUserIdIn(Collection<Long> userIds);
}
