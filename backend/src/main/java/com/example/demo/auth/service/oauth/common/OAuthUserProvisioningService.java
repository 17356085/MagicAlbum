package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.dto.apple.AppleIdTokenClaims;
import com.example.demo.auth.dto.github.GithubUserProfile;
import com.example.demo.auth.dto.google.GoogleUserProfile;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class OAuthUserProvisioningService {
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ConnectedAccountsService userConnectedAccountsService;
    private final PasswordEncoder passwordEncoder;
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public OAuthUserProvisioningService(UserRepository users, UserProfileRepository profiles,
            ConnectedAccountsService accounts, PasswordEncoder encoder,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this(users, profiles, accounts, encoder);
        transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    OAuthUserProvisioningService(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            ConnectedAccountsService userConnectedAccountsService,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.userConnectedAccountsService = userConnectedAccountsService;
        this.passwordEncoder = passwordEncoder;
    }

    static OAuthUserProvisioningService forTest(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            ConnectedAccountsService userConnectedAccountsService,
            PasswordEncoder passwordEncoder
    ) {
        return new OAuthUserProvisioningService(
                userRepository,
                userProfileRepository,
                userConnectedAccountsService,
                passwordEncoder
        );
    }

    public User resolveOrCreateGithubUser(GithubUserProfile githubUser) {
        return provision("github", String.valueOf(githubUser.getId()), () -> resolveGithub(githubUser));
    }

    private User resolveGithub(GithubUserProfile githubUser) {
        String externalId = String.valueOf(githubUser.getId());
        Long connectedUserId = userConnectedAccountsService.findUserIdByExternalAccount("github", externalId);
        if (connectedUserId != null) {
            return userRepository.findById(connectedUserId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth 绑定用户不存在"));
        }

        // GitHub's /user profile does not carry a verified-email assertion. Never
        // use its email (or a predictable fallback) to select an existing user.
        User user = createUserFromGithub(githubUser);

        userConnectedAccountsService.bindExternalAccount(user.getId(), "github", externalId, resolveDisplayName(githubUser));
        ensureUserProfile(user, githubUser);
        return user;
    }

    public User resolveOrCreateGoogleUser(GoogleUserProfile googleUser) {
        return provision("google", trimToEmpty(googleUser.getSub()), () -> resolveGoogle(googleUser));
    }

    private User resolveGoogle(GoogleUserProfile googleUser) {
        String externalId = trimToEmpty(googleUser.getSub());
        Long connectedUserId = userConnectedAccountsService.findUserIdByExternalAccount("google", externalId);
        if (connectedUserId != null) {
            return userRepository.findById(connectedUserId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth 绑定用户不存在"));
        }

        String email = normalizeProviderEmail(googleUser.getEmail());
        boolean emailVerified = isVerifiedEmail(googleUser.getEmailVerified(), email);
        rejectExistingVerifiedEmail(email, emailVerified);
        User user = createUserFromGoogle(googleUser, emailVerified);

        userConnectedAccountsService.bindExternalAccount(user.getId(), "google", externalId, resolveDisplayName(googleUser));
        ensureUserProfile(user, googleUser);
        return user;
    }

    public User resolveOrCreateAppleUser(AppleIdTokenClaims appleUser) {
        return provision("apple", trimToEmpty(appleUser.getSub()), () -> resolveApple(appleUser));
    }

    private User resolveApple(AppleIdTokenClaims appleUser) {
        String externalId = trimToEmpty(appleUser.getSub());
        Long connectedUserId = userConnectedAccountsService.findUserIdByExternalAccount("apple", externalId);
        if (connectedUserId != null) {
            return userRepository.findById(connectedUserId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth 绑定用户不存在"));
        }

        String email = normalizeProviderEmail(appleUser.getEmail());
        boolean emailVerified = isVerifiedEmail(appleUser.getEmailVerified(), email);
        rejectExistingVerifiedEmail(email, emailVerified);
        User user = createUserFromApple(appleUser, emailVerified);

        userConnectedAccountsService.bindExternalAccount(user.getId(), "apple", externalId, resolveDisplayName(appleUser));
        ensureUserProfile(user, appleUser);
        return user;
    }

    private User provision(String provider, String externalId, java.util.function.Supplier<User> action) {
        // Tests may use the four-argument constructor; application beans always receive a real transaction manager.
        if (transactions == null) return action.get();
        try {
            return transactions.execute(status -> action.get());
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            // The failed creation has rolled back before reading the concurrent winner in a fresh transaction.
            User winner = transactions.execute(status -> {
                Long id = userConnectedAccountsService.findUserIdByExternalAccount(provider, externalId);
                return id == null ? null : userRepository.findById(id).orElse(null);
            });
            if (winner != null) return winner;
            throw conflict;
        }
    }

    private User createUserFromGithub(GithubUserProfile githubUser) {
        User user = new User();
        user.setUsername(resolveUniqueUsername(githubUser));
        user.setEmail(createUniqueSyntheticOAuthEmail("github"));
        user.setEmailVerified(false);
        user.setPhone(generateSyntheticPhone(githubUser.getId()));
        user.setPhoneVerified(false);
        user.setPasswordHash(passwordEncoder.encode("oauth_" + UUID.randomUUID()));
        return userRepository.save(user);
    }

    private User createUserFromGoogle(GoogleUserProfile googleUser, boolean emailVerified) {
        User user = new User();
        user.setUsername(resolveUniqueUsername(googleUser));
        user.setEmail(emailVerified
                ? normalizeProviderEmail(googleUser.getEmail())
                : createUniqueSyntheticOAuthEmail("google"));
        user.setEmailVerified(emailVerified);
        user.setPhone(generateSyntheticPhone(googleUser.getSub()));
        user.setPhoneVerified(false);
        user.setPasswordHash(passwordEncoder.encode("oauth_" + UUID.randomUUID()));
        return userRepository.save(user);
    }

    private User createUserFromApple(AppleIdTokenClaims appleUser, boolean emailVerified) {
        User user = new User();
        user.setUsername(resolveUniqueUsername(appleUser));
        user.setEmail(emailVerified
                ? normalizeProviderEmail(appleUser.getEmail())
                : createUniqueSyntheticOAuthEmail("apple"));
        user.setEmailVerified(emailVerified);
        user.setPhone(generateSyntheticPhone(appleUser.getSub()));
        user.setPhoneVerified(false);
        user.setPasswordHash(passwordEncoder.encode("oauth_" + UUID.randomUUID()));
        return userRepository.save(user);
    }

    private void ensureUserProfile(User user, GithubUserProfile githubUser) {
        UserProfile profile = userProfileRepository.findById(user.getId()).orElseGet(UserProfile::new);
        profile.setUserId(user.getId());
        if (isBlank(profile.getNickname())) {
            profile.setNickname(resolveDisplayName(githubUser));
        }
        if (isBlank(profile.getAvatarUrl())) {
            profile.setAvatarUrl(trimToEmpty(githubUser.getAvatarUrl()));
        }
        userProfileRepository.save(profile);
    }

    private void ensureUserProfile(User user, GoogleUserProfile googleUser) {
        UserProfile profile = userProfileRepository.findById(user.getId()).orElseGet(UserProfile::new);
        profile.setUserId(user.getId());
        if (isBlank(profile.getNickname())) {
            profile.setNickname(resolveDisplayName(googleUser));
        }
        if (isBlank(profile.getAvatarUrl())) {
            profile.setAvatarUrl(trimToEmpty(googleUser.getPicture()));
        }
        userProfileRepository.save(profile);
    }

    private void ensureUserProfile(User user, AppleIdTokenClaims appleUser) {
        UserProfile profile = userProfileRepository.findById(user.getId()).orElseGet(UserProfile::new);
        profile.setUserId(user.getId());
        if (isBlank(profile.getNickname())) {
            profile.setNickname(resolveDisplayName(appleUser));
        }
        userProfileRepository.save(profile);
    }

    private String resolveUniqueUsername(GithubUserProfile githubUser) {
        String base = trimToEmpty(githubUser.getLogin()).replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isBlank()) {
            base = "github_user_" + githubUser.getId();
        }
        return ensureAvailableUsername(normalizeUsernameBase(base, "_gh"));
    }

    private String resolveUniqueUsername(GoogleUserProfile googleUser) {
        String base = trimToEmpty(googleUser.getEmail());
        if (base.contains("@")) {
            base = base.substring(0, base.indexOf('@'));
        }
        if (base.isBlank()) {
            base = trimToEmpty(googleUser.getName()).replaceAll("\\s+", "_");
        }
        base = base.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isBlank()) {
            base = "google_user_" + trimToEmpty(googleUser.getSub());
        }
        return ensureAvailableUsername(normalizeUsernameBase(base, "_gg"));
    }

    private String resolveUniqueUsername(AppleIdTokenClaims appleUser) {
        String base = trimToEmpty(appleUser.getEmail());
        if (base.contains("@")) {
            base = base.substring(0, base.indexOf('@'));
        }
        if (base.isBlank()) {
            base = "apple_user_" + trimToEmpty(appleUser.getSub());
        }
        base = base.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isBlank()) {
            base = "apple_user";
        }
        return ensureAvailableUsername(normalizeUsernameBase(base, "_ap"));
    }

    private String normalizeUsernameBase(String base, String suffixForShort) {
        String normalized = trimToEmpty(base);
        if (normalized.length() < 3) {
            normalized = normalized + suffixForShort;
        }
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    private String ensureAvailableUsername(String base) {
        String candidate = base;
        int seq = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = (base.length() > 42 ? base.substring(0, 42) : base) + "_" + seq++;
        }
        return candidate;
    }

    private void rejectExistingVerifiedEmail(String email, boolean emailVerified) {
        if (!emailVerified || !emailExists(email)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "该邮箱已绑定本地账号，请使用原登录方式");
    }

    private boolean emailExists(String email) {
        String normalized = normalizeProviderEmail(email);
        if (normalized.isBlank()) {
            return false;
        }
        List<User> matches = userRepository.findAllByEmailIgnoreCase(normalized);
        if (matches != null && !matches.isEmpty()) {
            return true;
        }
        return userRepository.findByEmail(normalized).isPresent() || userRepository.existsByEmail(normalized);
    }

    private String createUniqueSyntheticOAuthEmail(String provider) {
        String normalizedProvider = trimToEmpty(provider).toLowerCase(Locale.ROOT);
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = "oauth-" + normalizedProvider + "-"
                    + UUID.randomUUID().toString().replace("-", "") + "@oauth.invalid";
            if (!userRepository.existsByEmail(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法生成唯一的 OAuth 合成邮箱");
    }

    private boolean isVerifiedEmail(Boolean verified, String email) {
        return Boolean.TRUE.equals(verified) && !trimToEmpty(email).isBlank();
    }

    private String normalizeProviderEmail(String email) {
        return trimToEmpty(email).toLowerCase(Locale.ROOT);
    }

    private String resolveDisplayName(GithubUserProfile githubUser) {
        String name = trimToEmpty(githubUser.getName());
        if (!name.isBlank()) {
            return name;
        }
        return trimToEmpty(githubUser.getLogin());
    }

    private String resolveDisplayName(GoogleUserProfile googleUser) {
        String name = trimToEmpty(googleUser.getName());
        if (!name.isBlank()) {
            return name;
        }
        String email = trimToEmpty(googleUser.getEmail());
        if (email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return email;
    }

    private String resolveDisplayName(AppleIdTokenClaims appleUser) {
        String email = trimToEmpty(appleUser.getEmail());
        if (email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return "Apple用户";
    }

    private String generateSyntheticPhone(Long githubId) {
        long numeric = githubId == null ? Math.abs(UUID.randomUUID().hashCode()) : Math.abs(githubId);
        return generateSyntheticPhone(numeric);
    }

    private String generateSyntheticPhone(String seed) {
        long numeric = Math.abs(trimToEmpty(seed).hashCode());
        return generateSyntheticPhone(numeric);
    }

    private String generateSyntheticPhone(long numericSeed) {
        long numeric = Math.abs(numericSeed);
        String suffix = String.format("%08d", numeric % 100_000_000L);
        String candidate = "199" + suffix;
        while (userRepository.existsByPhone(candidate)) {
            numeric++;
            suffix = String.format("%08d", numeric % 100_000_000L);
            candidate = "199" + suffix;
        }
        return candidate;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
