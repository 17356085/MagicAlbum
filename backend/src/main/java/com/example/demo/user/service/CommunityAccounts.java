package com.example.demo.user.service;

import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only account views owned by the user domain for community consumers.
 *
 * <p>The service deliberately keeps the persistence entity and repository
 * behind small, immutable projections. Interaction lookups retain the
 * repository's pessimistic lock and are invoked by the governance transaction
 * that owns the lock order.</p>
 */
@Service
public class CommunityAccounts {

    private final UserRepository userRepository;

    public CommunityAccounts(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    /**
     * Finds and pessimistically locks an account for a governance interaction.
     * The caller's transaction owns the lock until it completes.
     */
    public Optional<AccountSummary> findByIdForInteraction(Long userId) {
        return userRepository.findByIdForInteraction(userId).map(CommunityAccounts::toAccountSummary);
    }

    /** Returns the paged account fields needed by governance user listings. */
    public Page<AccountSummary> search(String query, Pageable pageable) {
        return userRepository.search(query, pageable).map(CommunityAccounts::toAccountSummary);
    }

    /** Returns only the fields needed to resolve an initial configured admin. */
    public Optional<BootstrapAccount> findBootstrapById(Long userId) {
        return userRepository.findById(userId).map(CommunityAccounts::toBootstrapAccount);
    }

    /** Returns only the fields needed to resolve an email-selected admin. */
    public List<BootstrapAccount> findBootstrapByEmailIgnoreCase(String email) {
        return userRepository.findAllByEmailIgnoreCase(email).stream()
                .map(CommunityAccounts::toBootstrapAccount)
                .toList();
    }

    private static AccountSummary toAccountSummary(User user) {
        return new AccountSummary(user.getId(), user.getUsername());
    }

    private static BootstrapAccount toBootstrapAccount(User user) {
        return new BootstrapAccount(user.getId(), user.getEmail(), user.isEmailVerified());
    }

    /** Immutable identity used by governance, without exposing the User entity. */
    public record AccountSummary(Long id, String username) {
    }

    /** Immutable bootstrap selector projection, intentionally separate from AccountSummary. */
    public record BootstrapAccount(Long id, String email, boolean emailVerified) {
    }
}
