package com.example.demo.admin.security;

import com.example.demo.governance.service.GovernanceService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final GovernanceService governanceService;
    private final String configuredEmail;
    private final long configuredUserId;
    private volatile Long resolvedAdminUserId;

    /**
     * Kept for the existing focused unit tests. Production wiring uses the
     * four-argument constructor so persisted governance roles are authoritative.
     */
    public AdminAccessService(UserRepository userRepository,
                              @Value("${app.admin.email:}") String configuredEmail) {
        this(userRepository, null, configuredEmail, 0L);
    }

    public AdminAccessService(UserRepository userRepository,
                              GovernanceService governanceService,
                              @Value("${app.admin.email:}") String configuredEmail) {
        this(userRepository, governanceService, configuredEmail, 0L);
    }

    @Autowired
    public AdminAccessService(UserRepository userRepository,
                              GovernanceService governanceService,
                              @Value("${app.admin.email:}") String configuredEmail,
                              @Value("${app.admin.user-id:0}") long configuredUserId) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.governanceService = governanceService;
        this.configuredEmail = normalize(configuredEmail);
        this.configuredUserId = configuredUserId;
    }

    /**
     * Resolving in a bean lifecycle callback makes a non-empty configuration
     * fail application startup before the HTTP server can accept requests.
     */
    @PostConstruct
    void resolveConfiguredAdmin() {
        resolvedAdminUserId = null;
        if (configuredUserId < 0) {
            throw new IllegalStateException(
                    "Configured app.admin.user-id must be zero or a positive user id; startup aborted");
        }

        if (configuredUserId > 0) {
            User configuredUser = userRepository.findById(configuredUserId).orElse(null);
            if (configuredUser == null) {
                throw new IllegalStateException(
                        "Configured app.admin.user-id does not match an existing user account; startup aborted");
            }
            if (!configuredEmail.isEmpty()
                    && !configuredEmail.equalsIgnoreCase(configuredUser.getEmail())) {
                throw new IllegalStateException(
                        "Configured admin identity does not match the selected user account; startup aborted");
            }
            resolvedAdminUserId = configuredUserId;
            bootstrapResolvedAdmin();
            return;
        }

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

        User matchedUser = matches.get(0);
        Long userId = matchedUser.getId();
        if (userId == null) {
            throw new IllegalStateException(
                    "Configured app.admin.email matched a user without a persisted id; startup aborted");
        }
        if (!matchedUser.isEmailVerified()) {
            throw new IllegalStateException(
                    "Configured app.admin.email matched an unverified user account; startup aborted");
        }
        resolvedAdminUserId = userId;
        bootstrapResolvedAdmin();
    }

    private void bootstrapResolvedAdmin() {
        if (governanceService != null) {
            governanceService.bootstrapAdmin(resolvedAdminUserId);
        }
    }

    public boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        return governanceService == null
                ? userId.equals(resolvedAdminUserId)
                : governanceService.isAdmin(userId);
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
