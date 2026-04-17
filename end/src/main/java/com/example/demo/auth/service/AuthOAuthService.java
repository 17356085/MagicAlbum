package com.example.demo.auth.service;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.GithubAccessTokenResponse;
import com.example.demo.auth.dto.GithubUserProfile;
import com.example.demo.auth.dto.AppleIdTokenClaims;
import com.example.demo.auth.dto.AppleTokenResponse;
import com.example.demo.auth.dto.GoogleAccessTokenResponse;
import com.example.demo.auth.dto.GoogleUserProfile;
import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.user.connected.UserConnectedAccountsService;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthOAuthService {
    private final String frontendCallbackUrl;
    private final long stateTtlSeconds;
    private final String githubClientId;
    private final String githubRedirectUri;
    private final String githubScope;
    private final String googleClientId;
    private final String googleRedirectUri;
    private final String googleScope;
    private final String appleClientId;
    private final String appleRedirectUri;
    private final String appleScope;
    private final String wechatAppId;
    private final String wechatRedirectUri;
    private final String wechatScope;
    private final GithubOAuthClient githubOAuthClient;
    private final GoogleOAuthClient googleOAuthClient;
    private final AppleOAuthClient appleOAuthClient;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserConnectedAccountsService userConnectedAccountsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final Map<String, OAuthStateSession> stateStore = new ConcurrentHashMap<>();

    public AuthOAuthService(
            @Value("${app.auth.oauth.frontend-callback-url:http://localhost:5173/auth/oauth/callback}") String frontendCallbackUrl,
            @Value("${app.auth.oauth.state-ttl-seconds:300}") long stateTtlSeconds,
            @Value("${app.auth.oauth.github.client-id:}") String githubClientId,
            @Value("${app.auth.oauth.github.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/github}") String githubRedirectUri,
            @Value("${app.auth.oauth.github.scope:read:user user:email}") String githubScope,
            @Value("${app.auth.oauth.google.client-id:}") String googleClientId,
            @Value("${app.auth.oauth.google.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/google}") String googleRedirectUri,
            @Value("${app.auth.oauth.google.scope:openid email profile}") String googleScope,
            @Value("${app.auth.oauth.apple.client-id:}") String appleClientId,
            @Value("${app.auth.oauth.apple.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/apple}") String appleRedirectUri,
            @Value("${app.auth.oauth.apple.scope:name email}") String appleScope,
            @Value("${app.auth.oauth.wechat.app-id:}") String wechatAppId,
            @Value("${app.auth.oauth.wechat.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/wechat}") String wechatRedirectUri,
            @Value("${app.auth.oauth.wechat.scope:snsapi_login}") String wechatScope,
            GithubOAuthClient githubOAuthClient,
            GoogleOAuthClient googleOAuthClient,
            AppleOAuthClient appleOAuthClient,
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            UserConnectedAccountsService userConnectedAccountsService,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.frontendCallbackUrl = trimToEmpty(frontendCallbackUrl);
        this.stateTtlSeconds = Math.max(stateTtlSeconds, 60);
        this.githubClientId = trimToEmpty(githubClientId);
        this.githubRedirectUri = trimToEmpty(githubRedirectUri);
        this.githubScope = trimToEmpty(githubScope);
        this.googleClientId = trimToEmpty(googleClientId);
        this.googleRedirectUri = trimToEmpty(googleRedirectUri);
        this.googleScope = trimToEmpty(googleScope);
        this.appleClientId = trimToEmpty(appleClientId);
        this.appleRedirectUri = trimToEmpty(appleRedirectUri);
        this.appleScope = trimToEmpty(appleScope);
        this.wechatAppId = trimToEmpty(wechatAppId);
        this.wechatRedirectUri = trimToEmpty(wechatRedirectUri);
        this.wechatScope = trimToEmpty(wechatScope);
        this.githubOAuthClient = githubOAuthClient;
        this.googleOAuthClient = googleOAuthClient;
        this.appleOAuthClient = appleOAuthClient;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.userConnectedAccountsService = userConnectedAccountsService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    static AuthOAuthService forTest(
            String frontendCallbackUrl,
            long stateTtlSeconds,
            String githubClientId,
            String githubRedirectUri,
            String githubScope,
            String googleClientId,
            String googleRedirectUri,
            String googleScope,
            String appleClientId,
            String appleRedirectUri,
            String appleScope,
            String wechatAppId,
            String wechatRedirectUri,
            String wechatScope,
            GithubOAuthClient githubOAuthClient,
            GoogleOAuthClient googleOAuthClient,
            AppleOAuthClient appleOAuthClient,
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            UserConnectedAccountsService userConnectedAccountsService,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        return new AuthOAuthService(
                frontendCallbackUrl,
                stateTtlSeconds,
                githubClientId,
                githubRedirectUri,
                githubScope,
                googleClientId,
                googleRedirectUri,
                googleScope,
                appleClientId,
                appleRedirectUri,
                appleScope,
                wechatAppId,
                wechatRedirectUri,
                wechatScope,
                githubOAuthClient,
                googleOAuthClient,
                appleOAuthClient,
                userRepository,
                userProfileRepository,
                userConnectedAccountsService,
                passwordEncoder,
                jwtTokenProvider
        );
    }

    public String buildAuthorizeRedirect(OAuthProvider provider) {
        purgeExpiredStates();
        String state = UUID.randomUUID().toString().replace("-", "");
        stateStore.put(state, new OAuthStateSession(provider, OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(stateTtlSeconds)));

        if (provider == OAuthProvider.github) {
            if (githubClientId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub OAuth clientId 未配置");
            }
            return "https://github.com/login/oauth/authorize"
                    + "?client_id=" + encode(githubClientId)
                    + "&redirect_uri=" + encode(githubRedirectUri)
                    + "&scope=" + encode(githubScope)
                    + "&state=" + encode(state);
        }

        if (provider == OAuthProvider.google) {
            if (googleClientId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google OAuth clientId 未配置");
            }
            return "https://accounts.google.com/o/oauth2/v2/auth"
                    + "?client_id=" + encode(googleClientId)
                    + "&redirect_uri=" + encode(googleRedirectUri)
                    + "&response_type=code"
                    + "&scope=" + encode(googleScope.isBlank() ? "openid email profile" : googleScope)
                    + "&access_type=offline"
                    + "&prompt=consent"
                    + "&state=" + encode(state);
        }

        if (provider == OAuthProvider.apple) {
            if (appleClientId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Apple OAuth clientId 未配置");
            }
            return "https://appleid.apple.com/auth/authorize"
                    + "?client_id=" + encode(appleClientId)
                    + "&redirect_uri=" + encode(appleRedirectUri)
                    + "&response_type=code"
                    + "&response_mode=query"
                    + "&scope=" + encode(appleScope.isBlank() ? "name email" : appleScope)
                    + "&state=" + encode(state);
        }

        if (provider == OAuthProvider.wechat) {
            if (wechatAppId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录 AppID 未配置");
            }
            return "https://open.weixin.qq.com/connect/qrconnect"
                    + "?appid=" + encode(wechatAppId)
                    + "&redirect_uri=" + encode(wechatRedirectUri)
                    + "&response_type=code"
                    + "&scope=" + encode(wechatScope.isBlank() ? "snsapi_login" : wechatScope)
                    + "&state=" + encode(state)
                    + "#wechat_redirect";
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前 Provider 尚未接入 OAuth 骨架");
    }

    public String buildGithubCallbackRedirect(String code, String state, String error) {
        if (!consumeStateSafely(state)) {
            return buildProviderErrorRedirect(OAuthProvider.github, "OAuth state 无效或已过期");
        }

        StringBuilder url = new StringBuilder(resolveFrontendCallbackBase())
                .append(resolveFrontendCallbackBase().contains("?") ? "&" : "?")
                .append("provider=github");

        if (!trimToEmpty(error).isBlank()) {
            return buildProviderErrorRedirect(OAuthProvider.github, error);
        }

        if (trimToEmpty(code).isBlank()) {
            return buildProviderErrorRedirect(OAuthProvider.github, "缺少 GitHub OAuth code");
        }

        GithubAccessTokenResponse tokenResponse;
        GithubUserProfile githubUser;
        try {
            tokenResponse = githubOAuthClient.exchangeCode(code);
            githubUser = githubOAuthClient.fetchUserProfile(tokenResponse.getAccessToken());
        } catch (Exception ex) {
            return buildProviderErrorRedirect(OAuthProvider.github, "GitHub OAuth 登录失败，请检查应用密钥和回调配置");
        }

        User user = resolveOrCreateLocalUser(githubUser);
        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getUsername());

        return url
                .append("&status=success")
                .append("&accessToken=").append(encode(accessToken))
                .append("&username=").append(encode(user.getUsername()))
                .append("&userId=").append(user.getId())
                .toString();
    }

    public String buildGoogleCallbackRedirect(String code, String state, String error) {
        if (!consumeStateSafely(state)) {
            return buildProviderErrorRedirect(OAuthProvider.google, "OAuth state 无效或已过期");
        }

        StringBuilder url = new StringBuilder(resolveFrontendCallbackBase())
                .append(resolveFrontendCallbackBase().contains("?") ? "&" : "?")
                .append("provider=google");

        if (!trimToEmpty(error).isBlank()) {
            return buildProviderErrorRedirect(OAuthProvider.google, error);
        }

        if (trimToEmpty(code).isBlank()) {
            return buildProviderErrorRedirect(OAuthProvider.google, "缺少 Google 授权 code");
        }

        GoogleAccessTokenResponse tokenResponse;
        GoogleUserProfile googleUser;
        try {
            tokenResponse = googleOAuthClient.exchangeCode(code);
            googleUser = googleOAuthClient.fetchUserProfile(tokenResponse.getAccessToken());
        } catch (Exception ex) {
            return buildProviderErrorRedirect(OAuthProvider.google, "Google OAuth 登录失败，请检查客户端密钥、回调地址和网络连通性");
        }

        User user = resolveOrCreateLocalUser(googleUser);
        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getUsername());

        return url
                .append("&status=success")
                .append("&accessToken=").append(encode(accessToken))
                .append("&username=").append(encode(user.getUsername()))
                .append("&userId=").append(user.getId())
                .toString();
    }

    public String buildWechatCallbackRedirect(String code, String state, String error) {
        if (!consumeStateSafely(state)) {
            return buildProviderErrorRedirect(OAuthProvider.wechat, "OAuth state 无效或已过期");
        }

        StringBuilder url = new StringBuilder(resolveFrontendCallbackBase())
                .append(resolveFrontendCallbackBase().contains("?") ? "&" : "?")
                .append("provider=wechat");

        if (!trimToEmpty(error).isBlank()) {
            return buildProviderErrorRedirect(OAuthProvider.wechat, error);
        }

        if (trimToEmpty(code).isBlank()) {
            return buildProviderErrorRedirect(OAuthProvider.wechat, "缺少微信授权 code");
        }

        return buildProviderErrorRedirect(OAuthProvider.wechat, "微信登录骨架已接入，需配置可访问域名后继续联调");
    }

    public String buildAppleCallbackRedirect(String code, String state, String error) {
        if (!consumeStateSafely(state)) {
            return buildProviderErrorRedirect(OAuthProvider.apple, "OAuth state 无效或已过期");
        }

        StringBuilder url = new StringBuilder(resolveFrontendCallbackBase())
                .append(resolveFrontendCallbackBase().contains("?") ? "&" : "?")
                .append("provider=apple");

        if (!trimToEmpty(error).isBlank()) {
            return buildProviderErrorRedirect(OAuthProvider.apple, error);
        }

        if (trimToEmpty(code).isBlank()) {
            return buildProviderErrorRedirect(OAuthProvider.apple, "缺少 Apple 授权 code");
        }

        AppleTokenResponse tokenResponse;
        AppleIdTokenClaims appleUser;
        try {
            tokenResponse = appleOAuthClient.exchangeCode(code);
            appleUser = appleOAuthClient.parseIdToken(tokenResponse.getIdToken());
        } catch (Exception ex) {
            return buildProviderErrorRedirect(OAuthProvider.apple, "Apple 登录失败，请检查 client secret、回调地址和开发者配置");
        }

        User user = resolveOrCreateLocalUser(appleUser);
        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getUsername());

        return url
                .append("&status=success")
                .append("&accessToken=").append(encode(accessToken))
                .append("&username=").append(encode(user.getUsername()))
                .append("&userId=").append(user.getId())
                .toString();
    }

    private User resolveOrCreateLocalUser(GithubUserProfile githubUser) {
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

    private User resolveOrCreateLocalUser(GoogleUserProfile googleUser) {
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

    private User resolveOrCreateLocalUser(AppleIdTokenClaims appleUser) {
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
        if (normalized.isBlank()) return null;
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
        if (profile.getNickname() == null || profile.getNickname().isBlank()) {
            profile.setNickname(resolveDisplayName(githubUser));
        }
        if (profile.getAvatarUrl() == null || profile.getAvatarUrl().isBlank()) {
            profile.setAvatarUrl(trimToEmpty(githubUser.getAvatarUrl()));
        }
        userProfileRepository.save(profile);
    }

    private void ensureUserProfile(User user, GoogleUserProfile googleUser) {
        UserProfile profile = userProfileRepository.findById(user.getId()).orElseGet(UserProfile::new);
        profile.setUserId(user.getId());
        if (profile.getNickname() == null || profile.getNickname().isBlank()) {
            profile.setNickname(resolveDisplayName(googleUser));
        }
        if (profile.getAvatarUrl() == null || profile.getAvatarUrl().isBlank()) {
            profile.setAvatarUrl(trimToEmpty(googleUser.getPicture()));
        }
        userProfileRepository.save(profile);
    }

    private void ensureUserProfile(User user, AppleIdTokenClaims appleUser) {
        UserProfile profile = userProfileRepository.findById(user.getId()).orElseGet(UserProfile::new);
        profile.setUserId(user.getId());
        if (profile.getNickname() == null || profile.getNickname().isBlank()) {
            profile.setNickname(resolveDisplayName(appleUser));
        }
        userProfileRepository.save(profile);
    }

    private String resolveUniqueUsername(GithubUserProfile githubUser) {
        String base = trimToEmpty(githubUser.getLogin()).replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isBlank()) {
            base = "github_user_" + githubUser.getId();
        }
        if (base.length() < 3) {
            base = base + "_gh";
        }
        base = base.length() > 48 ? base.substring(0, 48) : base;

        String candidate = base;
        int seq = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = (base.length() > 42 ? base.substring(0, 42) : base) + "_" + seq++;
        }
        return candidate;
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
        if (base.length() < 3) {
            base = base + "_gg";
        }
        base = base.length() > 48 ? base.substring(0, 48) : base;

        String candidate = base;
        int seq = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = (base.length() > 42 ? base.substring(0, 42) : base) + "_" + seq++;
        }
        return candidate;
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
        if (base.length() < 3) {
            base = base + "_ap";
        }
        base = base.length() > 48 ? base.substring(0, 48) : base;

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
        String normalizedExternalId = trimToEmpty(externalId)
                .replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (normalizedExternalId.isBlank()) {
            normalizedExternalId = UUID.randomUUID().toString().replace("-", "");
        }
        return normalizedProvider + "_" + normalizedExternalId + "@oauth.local";
    }

    private String resolveDisplayName(GithubUserProfile githubUser) {
        String name = trimToEmpty(githubUser.getName());
        if (!name.isBlank()) return name;
        return trimToEmpty(githubUser.getLogin());
    }

    private String resolveDisplayName(GoogleUserProfile googleUser) {
        String name = trimToEmpty(googleUser.getName());
        if (!name.isBlank()) return name;
        String email = trimToEmpty(googleUser.getEmail());
        if (email.contains("@")) return email.substring(0, email.indexOf('@'));
        return email;
    }

    private String resolveDisplayName(AppleIdTokenClaims appleUser) {
        String email = trimToEmpty(appleUser.getEmail());
        if (email.contains("@")) return email.substring(0, email.indexOf('@'));
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

    private OAuthStateSession consumeState(String state) {
        String normalized = trimToEmpty(state);
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 OAuth state");
        }

        purgeExpiredStates();
        OAuthStateSession session = stateStore.remove(normalized);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state 无效或已过期");
        }
        return session;
    }

    private boolean consumeStateSafely(String state) {
        try {
            consumeState(state);
            return true;
        } catch (ResponseStatusException ex) {
            return false;
        }
    }

    private void purgeExpiredStates() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        stateStore.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String encode(String value) {
        return URLEncoder.encode(trimToEmpty(value), StandardCharsets.UTF_8);
    }

    private String resolveFrontendCallbackBase() {
        return frontendCallbackUrl.isBlank()
                ? "http://localhost:5173/auth/oauth/callback"
                : frontendCallbackUrl;
    }

    public String buildProviderErrorRedirect(OAuthProvider provider, String error) {
        String base = resolveFrontendCallbackBase();
        return new StringBuilder(base)
                .append(base.contains("?") ? "&" : "?")
                .append("provider=").append(encode(provider.name()))
                .append("&status=error")
                .append("&error=").append(encode(error))
                .toString();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record OAuthStateSession(
            OAuthProvider provider,
            OffsetDateTime expiresAt
    ) {}
}
