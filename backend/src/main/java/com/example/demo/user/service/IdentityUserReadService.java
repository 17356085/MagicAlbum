package com.example.demo.user.service;

import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Persistent Identity implementation of the narrow user read contract. */
@Service
public class IdentityUserReadService implements UserReadService {
    private final UserRepository userRepository;

    public IdentityUserReadService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean existsById(Long userId) {
        return userId != null && userRepository.existsById(userId);
    }

    @Override
    public Optional<String> displayNameById(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId).map(User::getUsername);
    }

    @Override
    public Optional<Long> findIdByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return userRepository.findByUsername(username).map(User::getId);
    }
}
