package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.common.RedisKeys;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.auth.service.apple.AppleOAuthClient;
import com.example.demo.auth.service.github.GithubOAuthClient;
import com.example.demo.auth.service.google.GoogleOAuthClient;
import com.example.demo.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthOAuthService {
    private final String frontendCallbackUrl;
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
    private final OAuthStateService oauthStateService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final long authorizationCodeTtlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, AuthorizationCodeRecord> authorizationCodes = new ConcurrentHashMap<>();
    private static final DefaultRedisScript<String> OAUTH_CODE_EXCHANGE_SCRIPT = codeExchangeScript();

    private static DefaultRedisScript<String> codeExchangeScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/oauth-code-exchange.lua"));
        script.setResultType(String.class);
        return script;
    }

    public AuthOAuthService(
            @Value("${app.auth.oauth.frontend-callback-url:http://localhost:5173/auth/oauth/callback}") String frontendCallbackUrl,
            @Value("${app.auth.oauth.github.client-id:}") String githubClientId,
            @Value("${app.auth.oauth.github.redirect-uri:http://localhost:9000/api/v1/auth/oauth/callback/github}") String githubRedirectUri,
            @Value("${app.auth.oauth.github.scope:read:user user:email}") String githubScope,
            @Value("${app.auth.oauth.google.client-id:}") String googleClientId,
            @Value("${app.auth.oauth.google.redirect-uri:http://localhost:9000/api/v1/auth/oauth/callback/google}") String googleRedirectUri,
            @Value("${app.auth.oauth.google.scope:openid email profile}") String googleScope,
            @Value("${app.auth.oauth.apple.client-id:}") String appleClientId,
            @Value("${app.auth.oauth.apple.redirect-uri:http://localhost:9000/api/v1/auth/oauth/callback/apple}") String appleRedirectUri,
            @Value("${app.auth.oauth.apple.scope:name email}") String appleScope,
            @Value("${app.auth.oauth.wechat.app-id:}") String wechatAppId,
            @Value("${app.auth.oauth.wechat.redirect-uri:http://localhost:9000/api/v1/auth/oauth/callback/wechat}") String wechatRedirectUri,
            @Value("${app.auth.oauth.wechat.scope:snsapi_login}") String wechatScope,
            OAuthStateService oauthStateService,
            JwtTokenProvider jwtTokenProvider,
            UserRepository userRepository,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.auth.oauth.authorization-code-ttl-seconds:90}") long authorizationCodeTtlSeconds
    ) {
        this.frontendCallbackUrl = trimToEmpty(frontendCallbackUrl);
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
        this.oauthStateService = oauthStateService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
        this.authorizationCodeTtlSeconds = Math.max(60, Math.min(authorizationCodeTtlSeconds, 120));
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
            com.example.demo.user.repo.UserRepository userRepository,
            com.example.demo.user.repo.UserProfileRepository userProfileRepository,
            com.example.demo.user.connected.service.ConnectedAccountsService userConnectedAccountsService,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        return new AuthOAuthService(
                frontendCallbackUrl,
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
                OAuthStateService.forTest(stateTtlSeconds)
                , jwtTokenProvider, userRepository, null, 90
        );
    }

    public String buildAuthorizeRedirect(OAuthProvider provider, String codeChallenge) {
        OAuthBrowserProof.requireChallenge(codeChallenge);
        if (provider == OAuthProvider.github) {
            return buildGithubAuthorizeRedirect(codeChallenge);
        }
        if (provider == OAuthProvider.google) {
            return buildGoogleAuthorizeRedirect(codeChallenge);
        }
        if (provider == OAuthProvider.apple) {
            return buildAppleAuthorizeRedirect(codeChallenge);
        }
        if (provider == OAuthProvider.wechat) {
            return buildWechatAuthorizeRedirect(codeChallenge);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前 Provider 尚未接入 OAuth 骨架");
    }

    String buildGithubAuthorizeRedirect(String codeChallenge) {
        String state = oauthStateService.issue(OAuthProvider.github, codeChallenge);
        if (githubClientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub OAuth clientId 未配置");
        }
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + encode(githubClientId)
                + "&redirect_uri=" + encode(githubRedirectUri)
                + "&scope=" + encode(githubScope)
                + "&state=" + encode(state);
    }

    String buildGoogleAuthorizeRedirect(String codeChallenge) {
        String state = oauthStateService.issue(OAuthProvider.google, codeChallenge);
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

    String buildAppleAuthorizeRedirect(String codeChallenge) {
        String state = oauthStateService.issue(OAuthProvider.apple, codeChallenge);
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

    String buildWechatAuthorizeRedirect(String codeChallenge) {
        String state = oauthStateService.issue(OAuthProvider.wechat, codeChallenge);
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

    public String consumeStateProof(OAuthProvider provider, String state) {
        try { return oauthStateService.consume(provider, state); }
        catch (ResponseStatusException rejected) {
            if (rejected.getStatusCode().value() >= 500) throw rejected;
            return null;
        }
    }

    public String buildSuccessRedirect(OAuthProvider provider, User user, String codeChallenge) {
        String authorizationCode = issueAuthorizationCode(provider, user, codeChallenge);
        String base = resolveFrontendCallbackBase();
        return new StringBuilder(base)
                .append(base.contains("?") ? "&" : "?")
                .append("provider=").append(encode(provider.name()))
                .append("&status=success")
                .append("&code=").append(encode(authorizationCode))
                .toString();
    }

    public LoginResponse exchangeAuthorizationCode(String code, String codeVerifier) {
        String codeChallenge = OAuthBrowserProof.challenge(codeVerifier);
        String normalizedCode = trimToEmpty(code);
        if (normalizedCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 OAuth code");
        }

        purgeExpiredAuthorizationCodes();
        if (redisTemplate != null) {
            try {
                String result = redisTemplate.execute(
                        OAUTH_CODE_EXCHANGE_SCRIPT,
                        java.util.List.of(RedisKeys.oauthCode(normalizedCode)),
                        String.valueOf(OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond()), codeChallenge
                );
                if (result != null) {
                    if ("__MISSING__".equals(result) || "__EXPIRED__".equals(result) || "__BINDING__".equals(result)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth code 无效或已过期");
                    }
                    return issueLogin(Long.valueOf(result));
                }
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OAuth 登录交换服务暂不可用");
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OAuth 登录交换服务暂不可用");
            }
        }

        java.util.concurrent.atomic.AtomicReference<AuthorizationCodeRecord> accepted = new java.util.concurrent.atomic.AtomicReference<>();
        authorizationCodes.compute(normalizedCode, (key, candidate) -> {
            if (candidate == null || !candidate.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) return null;
            if (!MessageDigest.isEqual(candidate.codeChallenge().getBytes(StandardCharsets.US_ASCII),
                    codeChallenge.getBytes(StandardCharsets.US_ASCII))) return candidate;
            accepted.set(candidate);
            return null;
        });
        AuthorizationCodeRecord record = accepted.get();
        if (record == null || record.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth code 无效或已过期");
        }
        return new LoginResponse(
                jwtTokenProvider.generateToken(record.userId(), record.username()),
                toUserDto(record)
        );
    }

    private LoginResponse issueLogin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth 用户不存在"));
        return new LoginResponse(
                jwtTokenProvider.generateToken(user.getId(), user.getUsername()),
                toUserDto(user)
        );
    }

    private String issueAuthorizationCode(OAuthProvider provider, User user, String codeChallenge) {
        OAuthBrowserProof.requireChallenge(codeChallenge);
        if (provider == null || user == null || user.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth 登录用户信息不完整");
        }
        purgeExpiredAuthorizationCodes();
        String code = generateAuthorizationCode();
        AuthorizationCodeRecord record = new AuthorizationCodeRecord(
                provider,
                user.getId(),
                trimToEmpty(user.getUsername()),
                trimToEmpty(user.getEmail()),
                trimToEmpty(user.getPhone()),
                user.getCreatedAt(),
                codeChallenge,
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(authorizationCodeTtlSeconds)
        );
        if (redisTemplate == null) {
            authorizationCodes.put(code, record);
        } else {
            storeAuthorizationCode(code, record);
        }
        return code;
    }

    private boolean storeAuthorizationCode(String code, AuthorizationCodeRecord record) {
        if (redisTemplate == null) {
            return false;
        }
        try {
            Map<String, String> values = new HashMap<>();
            values.put("provider", record.provider().name());
            values.put("userId", String.valueOf(record.userId()));
            values.put("expiresAt", String.valueOf(record.expiresAt().toEpochSecond()));
            values.put("codeChallenge", record.codeChallenge());
            redisTemplate.opsForHash().putAll(RedisKeys.oauthCode(code), values);
            redisTemplate.expire(RedisKeys.oauthCode(code), Duration.ofSeconds(authorizationCodeTtlSeconds));
            return true;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OAuth code 服务暂不可用");
        }
    }

    private void purgeExpiredAuthorizationCodes() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        authorizationCodes.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String generateAuthorizationCode() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private UserDto toUserDto(AuthorizationCodeRecord record) {
        UserDto dto = new UserDto();
        dto.setId(record.userId());
        dto.setUsername(record.username());
        dto.setEmail(record.email());
        dto.setPhone(record.phone());
        dto.setCreatedAt(record.createdAt());
        return dto;
    }

    private UserDto toUserDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }

    String encode(String value) {
        return URLEncoder.encode(trimToEmpty(value), StandardCharsets.UTF_8);
    }

    String resolveFrontendCallbackBase() {
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

    private record AuthorizationCodeRecord(
            OAuthProvider provider,
            Long userId,
            String username,
            String email,
            String phone,
            java.time.OffsetDateTime createdAt,
            String codeChallenge,
            OffsetDateTime expiresAt
    ) {
    }
}
