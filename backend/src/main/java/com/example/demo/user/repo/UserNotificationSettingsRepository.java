package com.example.demo.user.repo;

import com.example.demo.user.entity.UserNotificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationSettingsRepository extends JpaRepository<UserNotificationSettings, Long> {
}
