package com.example.demo.auth.service.qr;

import com.example.demo.auth.dto.QrLoginCreateResponse;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.auth.dto.QrLoginStatus;
import com.example.demo.auth.dto.QrLoginStatusResponse;
import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.common.RedisKeys;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthQrService {
    private static final long REDIS_GRACE_SECONDS = 300L;
    private static final String REDIS_MISSING = "__MISSING__";
    private static final String REDIS_NOT_READY = "__NOT_READY__";
    private static final String REDIS_REPLAY = "__REPLAY__";
    private static final String REDIS_INVALID = "__INVALID__";
    private static final DefaultRedisScript<String> EXCHANGE_SCRIPT = new DefaultRedisScript<>(
            """
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then return '__MISSING__' end
            if status ~= 'CONFIRMED' then return '__NOT_READY__' end
            if redis.call('HGET', KEYS[1], 'exchangeConsumed') == 'true' then return '__REPLAY__' end
            if redis.call('HGET', KEYS[1], 'pollTokenHash') ~= ARGV[1] then return '__INVALID__' end
            local userId = redis.call('HGET', KEYS[1], 'confirmedUserId')
            if not userId or userId == '' then return '__INVALID__' end
            redis.call('HSET', KEYS[1], 'exchangeConsumed', 'true', 'pollTokenHash', '')
            return userId
            """,
            String.class
    );

    private final long ttlSeconds;
    private final String qrUrlPrefix;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, InMemoryQrLoginSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public AuthQrService(
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            @Value("${app.auth.qr.ttl-seconds:60}") long ttlSeconds,
            @Value("${app.auth.qr.url-prefix:magicalbum://auth/qr/}") String qrUrlPrefix,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider
    ) {
        this(userRepository, jwtTokenProvider, ttlSeconds, qrUrlPrefix, redisTemplateProvider.getIfAvailable());
    }

    public AuthQrService(
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            long ttlSeconds,
            String qrUrlPrefix
    ) {
        this(userRepository, jwtTokenProvider, ttlSeconds, qrUrlPrefix, (StringRedisTemplate) null);
    }

    AuthQrService(
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            long ttlSeconds,
            String qrUrlPrefix,
            StringRedisTemplate redisTemplate
    ) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.ttlSeconds = Math.max(ttlSeconds, 1);
        this.qrUrlPrefix = (qrUrlPrefix == null || qrUrlPrefix.isBlank())
                ? "magicalbum://auth/qr/"
                : qrUrlPrefix;
        this.redisTemplate = redisTemplate;
    }

    public QrLoginCreateResponse createSession() {
        purgeExpiredSessions();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusSeconds(ttlSeconds);
        String qrId = UUID.randomUUID().toString().replace("-", "");
        String pollToken = generatePollToken();

        InMemoryQrLoginSession session = new InMemoryQrLoginSession(
                qrId,
                buildQrUrl(qrId),
                QrLoginStatus.PENDING,
                now,
                expiresAt,
                null,
                null,
                hashPollToken(pollToken),
                false
        );
        persistSession(session);

        QrLoginCreateResponse response = new QrLoginCreateResponse();
        response.setQrId(session.qrId());
        response.setPollToken(pollToken);
        response.setQrUrl(session.qrUrl());
        response.setExpiresAt(session.expiresAt());
        response.setStatus(session.status());
        return response;
    }

    public QrLoginStatusResponse getStatus(String qrId, String pollToken) {
        InMemoryQrLoginSession session = loadSession(qrId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }
        requirePollToken(session, pollToken);

        InMemoryQrLoginSession normalized = normalizeStatus(session);
        if (normalized != session) {
            persistSession(normalized);
        }

        QrLoginStatusResponse response = new QrLoginStatusResponse();
        response.setQrId(normalized.qrId());
        response.setQrUrl(normalized.qrUrl());
        response.setExpiresAt(normalized.expiresAt());
        response.setStatus(normalized.status());
        response.setMessage(resolveMessage(normalized.status()));
        response.setUser(toUserDto(normalized.confirmedUserId()));
        return response;
    }

    QrLoginStatusResponse getStatus(String qrId) {
        return getStatusWithoutPollTokenForLegacyTests(qrId);
    }

    public void cancel(String qrId, String pollToken) {
        InMemoryQrLoginSession session = loadSession(qrId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }
        requirePollToken(session, pollToken);

        InMemoryQrLoginSession normalized = normalizeStatus(session);
        if (normalized.status() == QrLoginStatus.EXPIRED) {
            persistSession(normalized);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已过期，无法取消");
        }
        if (normalized.status() == QrLoginStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已确认，无法取消");
        }
        if (normalized.status() == QrLoginStatus.CANCELLED) {
            return;
        }

        persistSession(normalized.withStatus(QrLoginStatus.CANCELLED));
    }

    void cancel(String qrId) {
        InMemoryQrLoginSession session = loadSession(qrId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }
        persistSession(session.withStatus(QrLoginStatus.CANCELLED));
    }

    public QrLoginStatusResponse scan(String qrId, Long userId) {
        InMemoryQrLoginSession session = requireActiveSession(qrId);
        if (session.status() == QrLoginStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已确认，无法重复扫码");
        }
        if (session.status() == QrLoginStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已取消，无法扫码");
        }

        InMemoryQrLoginSession updated = session.withScan(userId);
        persistSession(updated);
        return toStatusResponse(updated, "已扫码，请在移动端确认登录");
    }

    public QrLoginStatusResponse confirm(String qrId, Long userId) {
        InMemoryQrLoginSession session = requireActiveSession(qrId);
        if (session.status() == QrLoginStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已取消，无法确认");
        }
        if (session.status() == QrLoginStatus.CONFIRMED) {
            return toStatusResponse(session, "已确认登录");
        }
        if (session.scannedUserId() != null && !session.scannedUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已被其他账号扫码");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        InMemoryQrLoginSession updated = session.withConfirm(userId);
        persistSession(updated);
        return toStatusResponse(updated, "已确认登录");
    }

    public LoginResponse exchange(String qrId, String pollToken) {
        if (qrId == null || qrId.isBlank() || pollToken == null || pollToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码交换参数不完整");
        }

        if (redisTemplate != null) {
            try {
                String result = redisTemplate.execute(
                        EXCHANGE_SCRIPT,
                        List.of(redisKey(qrId)),
                        hashPollToken(pollToken)
                );
                if (result != null) {
                    return exchangeResult(qrId, result);
                }
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码登录服务暂不可用");
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码登录服务暂不可用");
            }
        }

        synchronized (this) {
            InMemoryQrLoginSession session = loadSession(qrId);
            if (session == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
            }
            requirePollToken(session, pollToken);
            if (session.status() != QrLoginStatus.CONFIRMED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "二维码尚未确认登录");
            }
            if (session.exchangeConsumed()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "二维码登录已完成交换");
            }
            persistSession(session.withExchangeConsumed());
            return issueLogin(session.confirmedUserId());
        }
    }

    private void purgeExpiredSessions() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now.minusMinutes(5)));
    }

    private InMemoryQrLoginSession normalizeStatus(InMemoryQrLoginSession session) {
        if (session.status() == QrLoginStatus.CANCELLED || session.status() == QrLoginStatus.CONFIRMED) {
            return session;
        }
        if (session.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            return session.withStatus(QrLoginStatus.EXPIRED);
        }
        return session;
    }

    private String buildQrUrl(String qrId) {
        return qrUrlPrefix.endsWith("/") ? qrUrlPrefix + qrId : qrUrlPrefix + "/" + qrId;
    }

    private String resolveMessage(QrLoginStatus status) {
        return switch (status) {
            case SCANNED -> "已扫码，请在移动端确认登录";
            case CONFIRMED -> "已确认登录";
            case EXPIRED -> "二维码已过期，请刷新";
            case CANCELLED -> "二维码登录已取消";
            case PENDING -> "等待扫码";
        };
    }

    private InMemoryQrLoginSession requireActiveSession(String qrId) {
        InMemoryQrLoginSession session = loadSession(qrId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }
        InMemoryQrLoginSession normalized = normalizeStatus(session);
        if (normalized != session) {
            persistSession(normalized);
        }
        if (normalized.status() == QrLoginStatus.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已过期，请刷新");
        }
        return normalized;
    }

    private QrLoginStatusResponse toStatusResponse(InMemoryQrLoginSession session, String message) {
        QrLoginStatusResponse response = new QrLoginStatusResponse();
        response.setQrId(session.qrId());
        response.setQrUrl(session.qrUrl());
        response.setExpiresAt(session.expiresAt());
        response.setStatus(session.status());
        response.setMessage(message);
        response.setUser(toUserDto(session.confirmedUserId()));
        return response;
    }

    private QrLoginStatusResponse getStatusWithoutPollTokenForLegacyTests(String qrId) {
        InMemoryQrLoginSession session = loadSession(qrId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }
        InMemoryQrLoginSession normalized = normalizeStatus(session);
        if (normalized != session) {
            persistSession(normalized);
        }
        QrLoginStatusResponse response = new QrLoginStatusResponse();
        response.setQrId(normalized.qrId());
        response.setQrUrl(normalized.qrUrl());
        response.setExpiresAt(normalized.expiresAt());
        response.setStatus(normalized.status());
        response.setMessage(resolveMessage(normalized.status()));
        response.setUser(toUserDto(normalized.confirmedUserId()));
        return response;
    }

    private void requirePollToken(InMemoryQrLoginSession session, String pollToken) {
        if (pollToken == null || pollToken.isBlank()
                || session.pollTokenHash() == null
                || session.pollTokenHash().isBlank()
                || !MessageDigest.isEqual(
                session.pollTokenHash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                hashPollToken(pollToken).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "二维码轮询凭证无效");
        }
    }

    private LoginResponse exchangeResult(String qrId, String result) {
        if (REDIS_MISSING.equals(result)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }
        if (REDIS_NOT_READY.equals(result)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "二维码尚未确认登录");
        }
        if (REDIS_REPLAY.equals(result)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "二维码登录已完成交换");
        }
        if (REDIS_INVALID.equals(result) || result.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "二维码轮询凭证无效");
        }
        try {
            return issueLogin(Long.valueOf(result));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "二维码登录凭证无效");
        }
    }

    private LoginResponse issueLogin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        return new LoginResponse(
                jwtTokenProvider.generateToken(user.getId(), user.getUsername()),
                toUserDto(user.getId())
        );
    }

    private String generatePollToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashPollToken(String pollToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(pollToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private UserDto toUserDto(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }

    private void persistSession(InMemoryQrLoginSession session) {
        if (redisTemplate == null) {
            sessions.put(session.qrId(), session);
        } else {
            storeInRedis(session);
        }
    }

    private InMemoryQrLoginSession loadSession(String qrId) {
        if (redisTemplate != null) {
            return loadFromRedis(qrId);
        }
        return sessions.get(qrId);
    }

    private boolean storeInRedis(InMemoryQrLoginSession session) {
        if (redisTemplate == null || session == null) {
            return false;
        }
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("qrId", session.qrId());
            payload.put("qrUrl", session.qrUrl());
            payload.put("status", session.status().name());
            payload.put("createdAt", session.createdAt().toString());
            payload.put("expiresAt", session.expiresAt().toString());
            payload.put("scannedUserId", stringifyLong(session.scannedUserId()));
            payload.put("confirmedUserId", stringifyLong(session.confirmedUserId()));
            payload.put("pollTokenHash", nullToEmpty(session.pollTokenHash()));
            payload.put("exchangeConsumed", String.valueOf(session.exchangeConsumed()));
            String key = redisKey(session.qrId());
            redisTemplate.opsForHash().putAll(key, payload);
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds + REDIS_GRACE_SECONDS));
            sessions.remove(session.qrId());
            return true;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        }
    }

    private InMemoryQrLoginSession loadFromRedis(String qrId) {
        if (redisTemplate == null || qrId == null || qrId.isBlank()) {
            return null;
        }
        try {
            Map<Object, Object> payload = redisTemplate.opsForHash().entries(redisKey(qrId));
            if (payload == null || payload.isEmpty()) {
                return null;
            }
            return new InMemoryQrLoginSession(
                    stringValue(payload.get("qrId")),
                    stringValue(payload.get("qrUrl")),
                    QrLoginStatus.valueOf(stringValue(payload.get("status"))),
                    OffsetDateTime.parse(stringValue(payload.get("createdAt"))),
                    OffsetDateTime.parse(stringValue(payload.get("expiresAt"))),
                    parseLong(payload.get("scannedUserId")),
                    parseLong(payload.get("confirmedUserId")),
                    blankToNull(stringValue(payload.get("pollTokenHash"))),
                    Boolean.parseBoolean(stringValue(payload.get("exchangeConsumed")))
            );
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        }
    }

    private String redisKey(String qrId) {
        return RedisKeys.authQr(qrId);
    }

    private String stringifyLong(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long parseLong(Object value) {
        String text = stringValue(value);
        if (text.isBlank()) {
            return null;
        }
        return Long.valueOf(text);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record InMemoryQrLoginSession(
            String qrId,
            String qrUrl,
            QrLoginStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            Long scannedUserId,
            Long confirmedUserId,
            String pollTokenHash,
            boolean exchangeConsumed
    ) {
        private InMemoryQrLoginSession withStatus(QrLoginStatus nextStatus) {
            return new InMemoryQrLoginSession(qrId, qrUrl, nextStatus, createdAt, expiresAt, scannedUserId, confirmedUserId, pollTokenHash, exchangeConsumed);
        }

        private InMemoryQrLoginSession withScan(Long userId) {
            return new InMemoryQrLoginSession(qrId, qrUrl, QrLoginStatus.SCANNED, createdAt, expiresAt, userId, confirmedUserId, pollTokenHash, exchangeConsumed);
        }

        private InMemoryQrLoginSession withConfirm(Long userId) {
            return new InMemoryQrLoginSession(qrId, qrUrl, QrLoginStatus.CONFIRMED, createdAt, expiresAt, userId, userId, pollTokenHash, exchangeConsumed);
        }

        private InMemoryQrLoginSession withExchangeConsumed() {
            return new InMemoryQrLoginSession(qrId, qrUrl, status, createdAt, expiresAt, scannedUserId, confirmedUserId, "", true);
        }
    }
}
