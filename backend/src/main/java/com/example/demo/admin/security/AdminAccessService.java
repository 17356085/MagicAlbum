package com.example.demo.admin.security;

import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/**
 * Resolves the optional local admin account once during application startup.
 *
 * <p>Authorization is intentionally based on the resolved persisted user id.
 * The configured email is only a startup-time selector and is never consulted
 * for individual requests.</p>
 */
@Service
public class AdminAccessService {

    private final UserRepository userRepository;
    private final String configuredEmail;
    private volatile Long resolvedAdminUserId;

    public AdminAccessService(UserRepository userRepository,
                              @Value("${app.admin.email:}") String configuredEmail) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.configuredEmail = normalize(configuredEmail);
    }

    /**
     * Resolving in a bean lifecycle callback makes a non-empty configuration
     * fail application startup before the HTTP server can accept requests.
     */
    @PostConstruct
    void resolveConfiguredAdmin() {
        resolvedAdminUserId = null;
        if (configuredEmail.isEmpty()) {
            return;
        }

        List<User> matches = userRepository.findAllByEmailIgnoreCase(configuredEmail);

        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "Configured app.admin.email does not match an existing user account; startup aborted");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Configured app.admin.email matches multiple user accounts; startup aborted");
        }

        Long userId = matches.get(0).getId();
        if (userId == null) {
            throw new IllegalStateException(
                    "Configured app.admin.email matched a user without a persisted id; startup aborted");
        }
        resolvedAdminUserId = userId;
    }

    public boolean isAdmin(Long userId) {
        return userId != null && userId.equals(resolvedAdminUserId);
    }

    public void requireAdmin(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!isAdmin(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
