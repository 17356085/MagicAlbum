package com.example.demo.user.service;

import java.util.Optional;

/** Identity-owned, read-only account lookups used by other application domains. */
public interface UserReadService {
    boolean existsById(Long userId);

    Optional<String> displayNameById(Long userId);

    Optional<Long> findIdByUsername(String username);
}
