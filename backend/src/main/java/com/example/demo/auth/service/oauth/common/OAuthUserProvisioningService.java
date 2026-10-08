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

import java.util.UUID;

@Service
public class OAuthUserProvisioningService {
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ConnectedAccountsService userConnectedAccountsService;
    private final PasswordEncoder passwordEncoder;

    public OAuthUserProvisioningService(
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
        String externalId = String.valueOf(githubUser.getId());
        Long connectedUserId = userConnectedAccountsService.findUserIdByExternalAccount("github", externalId);
        if (connectedUserId != null) {
            return userRepository.findById(connectedUserId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth 绑定用户不存在"));
        }

        User user = tryFindByEmail(githubUser.getEmail());
        if (user == null) {
            user = tryFindByEmail(buildOAuthFallbackEmail("github", externalId));
        }
        if (user == null) {
            user = createUserFromGithub(githubUser);
        }

        userConnectedAccountsService.bindExternalAccount(user.getId(), "github", externalId, resolveDisplayName(githubUser));
        ensureUserProfile(user, githubUser);
        return user;
    }

    public User resolveOrCreateGoogleUser(GoogleUserProfile googleUser) {
        String externalId = trimToEmpty(googleUser.getSub());
        Long connectedUserId = userConnectedAccountsService.findUserIdByExternalAccount("google", externalId);
        if (connectedUserId != null) {
            return userRepository.findById(connectedUserId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth 绑定用户不存在"));
        }

        User user = tryFindByEmail(googleUser.getEmail());
        if (user == null) {
            user = tryFindByEmail(buildOAuthFallbackEmail("google", externalId));
        }
        if (user == null) {
            user = createUserFromGoogle(googleUser);
        }

        userConnectedAccountsService.bindExternalAccount(user.getId(), "google", externalId, resolveDisplayName(googleUser));
        ensureUserProfile(user, googleUser);
        return user;
    }

    public User resolveOrCreateAppleUser(AppleIdTokenClaims appleUser) {
        String externalId = trimToEmpty(appleUser.getSub());
        Long connectedUserId = userConnectedAccountsService.findUserIdByExternalAccount("apple", externalId);
        if (connectedUserId != null) {
            return userRepository.findById(connectedUserId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth 绑定用户不存在"));
        }

        User user = tryFindByEmail(appleUser.getEmail());
        if (user == null) {
            user = tryFindByEmail(buildOAuthFallbackEmail("apple", externalId));
        }
        if (user == null) {
            user = createUserFromApple(appleUser);
        }

        userConnectedAccountsService.bindExternalAccount(user.getId(), "apple", externalId, resolveDisplayName(appleUser));
        ensureUserProfile(user, appleUser);
        return user;
    }

    private User tryFindByEmail(String email) {
        String normalized = trimToEmpty(email);
        if (normalized.isBlank()) {
            return null;
        }
        return userRepository.findByEmail(normalized).orElse(null);
    }

    private User createUserFromGithub(GithubUserProfile githubUser) {
        User user = new User();
        user.setUsername(resolveUniqueUsername(githubUser));
        user.setEmail(resolveEmail(githubUser));
        user.setPhone(generateSyntheticPhone(githubUser.getId()));
        user.setPasswordHash(passwordEncoder.encode("oauth_" + UUID.randomUUID()));
        return userRepository.save(user);
    }

    private User createUserFromGoogle(GoogleUserProfile googleUser) {
        User user = new User();
        user.setUsername(resolveUniqueUsername(googleUser));
        user.setEmail(resolveEmail(googleUser));
        user.setPhone(generateSyntheticPhone(googleUser.getSub()));
        user.setPasswordHash(passwordEncoder.encode("oauth_" + UUID.randomUUID()));
        return userRepository.save(user);
    }

    private User createUserFromApple(AppleIdTokenClaims appleUser) {
        User user = new User();
        user.setUsername(resolveUniqueUsername(appleUser));
        user.setEmail(resolveEmail(appleUser));
        user.setPhone(generateSyntheticPhone(appleUser.getSub()));
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

    private String resolveEmail(GithubUserProfile githubUser) {
        String email = trimToEmpty(githubUser.getEmail());
        if (!email.isBlank() && !userRepository.existsByEmail(email)) {
            return email;
        }
        return resolveAvailableOAuthFallbackEmail("github", String.valueOf(githubUser.getId()));
    }

    private String resolveEmail(GoogleUserProfile googleUser) {
        String email = trimToEmpty(googleUser.getEmail());
        if (!email.isBlank() && !userRepository.existsByEmail(email)) {
            return email;
        }
        return resolveAvailableOAuthFallbackEmail("google", trimToEmpty(googleUser.getSub()));
    }

    private String resolveEmail(AppleIdTokenClaims appleUser) {
        String email = trimToEmpty(appleUser.getEmail());
        if (!email.isBlank() && !userRepository.existsByEmail(email)) {
            return email;
        }
        return resolveAvailableOAuthFallbackEmail("apple", trimToEmpty(appleUser.getSub()));
    }

    private String resolveAvailableOAuthFallbackEmail(String provider, String externalId) {
        String base = buildOAuthFallbackEmail(provider, externalId);
        if (!userRepository.existsByEmail(base)) {
            return base;
        }

        int seq = 1;
        String candidate = buildOAuthFallbackEmail(provider, externalId + "_" + seq);
        while (userRepository.existsByEmail(candidate)) {
            seq++;
            candidate = buildOAuthFallbackEmail(provider, externalId + "_" + seq);
        }
        return candidate;
    }

    private String buildOAuthFallbackEmail(String provider, String externalId) {
        String normalizedProvider = trimToEmpty(provider).toLowerCase();
        String normalizedExternalId = trimToEmpty(externalId).replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (normalizedExternalId.isBlank()) {
            normalizedExternalId = UUID.randomUUID().toString().replace("-", "");
        }
        return normalizedProvider + "_" + normalizedExternalId + "@oauth.local";
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
