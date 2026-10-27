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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthQrService {
    private static final long REDIS_GRACE_SECONDS = 300L;
    private static final String REDIS_MISSING = "__MISSING__";
    private static final String REDIS_NOT_READY = "__NOT_READY__";
    private static final String REDIS_REPLAY = "__REPLAY__";
    private static final String REDIS_INVALID = "__INVALID__";
    private static final String REDIS_EXPIRED = "__EXPIRED__";
    private static final String REDIS_CANCELLED = "__CANCELLED__";
    private static final String REDIS_CONFIRMED = "__CONFIRMED__";
    private static final String REDIS_SCANNED = "__SCANNED__";
    private static final String REDIS_OWNER_CONFLICT = "__OWNER_CONFLICT__";
    private static final String REDIS_INVALID_TOKEN = "__INVALID_TOKEN__";
    private static final String REDIS_USER_MISSING = "__USER_MISSING__";
    private static final String REDIS_MALFORMED = "__MALFORMED__";

    private static final DefaultRedisScript<String> SCAN_SCRIPT = new DefaultRedisScript<>(
            """
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then return '__MISSING__' end
            local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAtEpochMillis'))
            if (not expiresAt or expiresAt <= tonumber(ARGV[1])) and status ~= 'CONFIRMED' and status ~= 'CANCELLED' and status ~= 'EXPIRED' then
                redis.call('HSET', KEYS[1], 'status', 'EXPIRED')
                status = 'EXPIRED'
            end
            if status == 'EXPIRED' then return '__EXPIRED__' end
            if status == 'CANCELLED' then return '__CANCELLED__' end
            if status == 'CONFIRMED' then return '__CONFIRMED__' end
            if status == 'SCANNED' then
                if redis.call('HGET', KEYS[1], 'scannedUserId') == ARGV[2] then return '__SCANNED__' end
                return '__OWNER_CONFLICT__'
            end
            if status ~= 'PENDING' then return '__MALFORMED__' end
            redis.call('HSET', KEYS[1], 'status', 'SCANNED', 'scannedUserId', ARGV[2])
            return '__SCANNED__'
            """,
            String.class
    );

    private static final DefaultRedisScript<String> CONFIRM_SCRIPT = new DefaultRedisScript<>(
            """
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then return '__MISSING__' end
            local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAtEpochMillis'))
            if (not expiresAt or expiresAt <= tonumber(ARGV[1])) and status ~= 'CONFIRMED' and status ~= 'CANCELLED' and status ~= 'EXPIRED' then
                redis.call('HSET', KEYS[1], 'status', 'EXPIRED')
                status = 'EXPIRED'
            end
            if status == 'EXPIRED' then return '__EXPIRED__' end
            if status == 'CANCELLED' then return '__CANCELLED__' end
            if status == 'CONFIRMED' then
                if redis.call('HGET', KEYS[1], 'confirmedUserId') == ARGV[2] then return '__CONFIRMED__' end
                return '__OWNER_CONFLICT__'
            end
            if status == 'SCANNED' and redis.call('HGET', KEYS[1], 'scannedUserId') ~= ARGV[2] then
                return '__OWNER_CONFLICT__'
            end
            if status ~= 'PENDING' and status ~= 'SCANNED' then return '__MALFORMED__' end
            if ARGV[3] ~= 'true' then return '__USER_MISSING__' end
            redis.call('HSET', KEYS[1], 'status', 'CONFIRMED', 'scannedUserId', ARGV[2], 'confirmedUserId', ARGV[2])
            return '__CONFIRMED__'
            """,
            String.class
    );

    private static final DefaultRedisScript<String> CANCEL_SCRIPT = new DefaultRedisScript<>(
            """
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then return '__MISSING__' end
            if redis.call('HGET', KEYS[1], 'pollTokenHash') ~= ARGV[2] then return '__INVALID_TOKEN__' end
            local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAtEpochMillis'))
            if (not expiresAt or expiresAt <= tonumber(ARGV[1])) and status ~= 'CONFIRMED' and status ~= 'CANCELLED' and status ~= 'EXPIRED' then
                redis.call('HSET', KEYS[1], 'status', 'EXPIRED')
                status = 'EXPIRED'
            end
            if status == 'EXPIRED' then return '__EXPIRED__' end
            if status == 'CONFIRMED' then return '__CONFIRMED__' end
            if status == 'CANCELLED' then return '__CANCELLED__' end
            if status ~= 'PENDING' and status ~= 'SCANNED' then return '__MALFORMED__' end
            redis.call('HSET', KEYS[1], 'status', 'CANCELLED')
            return '__CANCELLED__'
            """,
            String.class
    );
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

        QrLoginStatusResponse response = new QrLoginStatusResponse();
        response.setQrId(normalized.qrId());
        response.setQrUrl(normalized.qrUrl());
        response.setExpiresAt(normalized.expiresAt());
        response.setStatus(normalized.status());
        response.setMessage(resolveMessage(normalized.status()));
        return response;
    }

    public void cancel(String qrId, String pollToken) {
        if (qrId == null || qrId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码交换参数不完整");
        }
        MutationOutcome outcome;
        if (redisTemplate != null) {
            outcome = executeRedisMutation(
                    CANCEL_SCRIPT,
                    qrId,
                    String.valueOf(System.currentTimeMillis()),
                    hashPollTokenOrEmpty(pollToken)
            );
        } else {
            outcome = mutateLocal(qrId, MutationOperation.CANCEL, null, pollToken, true, true).outcome();
        }
        switch (outcome) {
            case SUCCESS_CANCELLED, CANCELLED -> { return; }
            case MISSING -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
            case INVALID_TOKEN -> throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "二维码轮询凭证无效");
            case EXPIRED -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已过期，无法取消");
            case CONFIRMED -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已确认，无法取消");
            default -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        }
    }

    void cancel(String qrId) {
        if (redisTemplate != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "二维码轮询凭证无效");
        }
        MutationOutcome outcome = mutateLocal(qrId, MutationOperation.CANCEL, null, null, false, true).outcome();
        if (outcome == MutationOutcome.MISSING) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }
        if (outcome == MutationOutcome.SUCCESS_CANCELLED || outcome == MutationOutcome.CANCELLED) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码无法取消");
    }

    public QrLoginStatusResponse scan(String qrId, Long userId) {
        requireQrId(qrId);
        requireUserId(userId);
        if (redisTemplate != null) {
            MutationOutcome outcome = executeRedisMutation(
                    SCAN_SCRIPT,
                    qrId,
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(userId)
            );
            if (outcome == MutationOutcome.SUCCESS_SCANNED) {
                return toStatusResponse(requireMutationSession(qrId), "已扫码，请在移动端确认登录");
            }
            throwScanFailure(outcome);
        }
        LocalMutation mutation = mutateLocal(qrId, MutationOperation.SCAN, userId, null, false, true);
        if (mutation.outcome() == MutationOutcome.SUCCESS_SCANNED) {
            return toStatusResponse(mutation.session(), "已扫码，请在移动端确认登录");
        }
        throwScanFailure(mutation.outcome());
        throw new IllegalStateException("unreachable");
    }

    public QrLoginStatusResponse confirm(String qrId, Long userId) {
        requireQrId(qrId);
        requireUserId(userId);
        boolean userExists = userRepository.findById(userId).isPresent();
        if (redisTemplate != null) {
            MutationOutcome outcome = executeRedisMutation(
                    CONFIRM_SCRIPT,
                    qrId,
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(userId),
                    String.valueOf(userExists)
            );
            if (outcome == MutationOutcome.SUCCESS_CONFIRMED) {
                return toStatusResponse(requireMutationSession(qrId), "已确认登录");
            }
            throwConfirmFailure(outcome);
        }
        LocalMutation mutation = mutateLocal(qrId, MutationOperation.CONFIRM, userId, null, false, userExists);
        if (mutation.outcome() == MutationOutcome.SUCCESS_CONFIRMED) {
            return toStatusResponse(mutation.session(), "已确认登录");
        }
        throwConfirmFailure(mutation.outcome());
        throw new IllegalStateException("unreachable");
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
        if (!session.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
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

    private QrLoginStatusResponse toStatusResponse(InMemoryQrLoginSession session, String message) {
        QrLoginStatusResponse response = new QrLoginStatusResponse();
        response.setQrId(session.qrId());
        response.setQrUrl(session.qrUrl());
        response.setExpiresAt(session.expiresAt());
        response.setStatus(session.status());
        response.setMessage(message);
        return response;
    }

    private void requirePollToken(InMemoryQrLoginSession session, String pollToken) {
        if (!pollTokenMatches(session, pollToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "二维码轮询凭证无效");
        }
    }

    private boolean pollTokenMatches(InMemoryQrLoginSession session, String pollToken) {
        return pollToken != null && !pollToken.isBlank()
                && session.pollTokenHash() != null
                && !session.pollTokenHash().isBlank()
                && MessageDigest.isEqual(
                session.pollTokenHash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                hashPollToken(pollToken).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private String hashPollTokenOrEmpty(String pollToken) {
        return pollToken == null || pollToken.isBlank() ? "" : hashPollToken(pollToken);
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
    }

    private void requireQrId(String qrId) {
        if (qrId == null || qrId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码参数不完整");
        }
    }

    private MutationOutcome executeRedisMutation(DefaultRedisScript<String> script, String qrId, Object... args) {
        if (redisTemplate == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        }
        try {
            String result = redisTemplate.execute(script, List.of(redisKey(qrId)), args);
            return decodeRedisMutation(result);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        }
    }

    private MutationOutcome decodeRedisMutation(String result) {
        if (result == null || result.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        }
        return switch (result) {
            case REDIS_MISSING -> MutationOutcome.MISSING;
            case REDIS_EXPIRED -> MutationOutcome.EXPIRED;
            case REDIS_CANCELLED -> MutationOutcome.CANCELLED;
            case REDIS_CONFIRMED -> MutationOutcome.SUCCESS_CONFIRMED;
            case REDIS_SCANNED -> MutationOutcome.SUCCESS_SCANNED;
            case REDIS_OWNER_CONFLICT -> MutationOutcome.OWNER_CONFLICT;
            case REDIS_INVALID_TOKEN -> MutationOutcome.INVALID_TOKEN;
            case REDIS_USER_MISSING -> MutationOutcome.USER_MISSING;
            case REDIS_MALFORMED, REDIS_INVALID -> MutationOutcome.INVALID;
            default -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        };
    }

    private InMemoryQrLoginSession requireMutationSession(String qrId) {
        InMemoryQrLoginSession session = loadSession(qrId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        }
        return session;
    }

    private void throwScanFailure(MutationOutcome outcome) {
        switch (outcome) {
            case MISSING -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
            case EXPIRED -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已过期，请刷新");
            case CANCELLED -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已取消，无法扫码");
            case SUCCESS_CONFIRMED, CONFIRMED -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已确认，无法重复扫码");
            case OWNER_CONFLICT -> throw new ResponseStatusException(HttpStatus.CONFLICT, "二维码已被其他账号扫码");
            default -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        }
    }

    private void throwConfirmFailure(MutationOutcome outcome) {
        switch (outcome) {
            case MISSING -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
            case EXPIRED -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已过期，请刷新");
            case CANCELLED -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已取消，无法确认");
            case OWNER_CONFLICT -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "二维码已被其他账号占用");
            case USER_MISSING -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
            default -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "二维码会话服务暂不可用");
        }
    }

    private LocalMutation mutateLocal(String qrId,
                                      MutationOperation operation,
                                      Long userId,
                                      String pollToken,
                                      boolean requirePollToken,
                                      boolean userExists) {
        AtomicReference<LocalMutation> result = new AtomicReference<>();
        sessions.compute(qrId, (key, current) -> {
            LocalMutation mutation = transitionLocal(current, operation, userId, pollToken, requirePollToken, userExists);
            result.set(mutation);
            return mutation.session();
        });
        return result.get();
    }

    private LocalMutation transitionLocal(InMemoryQrLoginSession current,
                                          MutationOperation operation,
                                          Long userId,
                                          String pollToken,
                                          boolean requirePollToken,
                                          boolean userExists) {
        if (current == null) {
            return new LocalMutation(MutationOutcome.MISSING, null);
        }
        if (operation == MutationOperation.CANCEL && requirePollToken && !pollTokenMatches(current, pollToken)) {
            return new LocalMutation(MutationOutcome.INVALID_TOKEN, current);
        }

        InMemoryQrLoginSession normalized = current;
        if (isExpirable(current.status()) && !current.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            normalized = current.withStatus(QrLoginStatus.EXPIRED);
        }
        if (normalized.status() == QrLoginStatus.EXPIRED) {
            return new LocalMutation(MutationOutcome.EXPIRED, normalized);
        }

        if (operation == MutationOperation.SCAN) {
            return switch (normalized.status()) {
                case PENDING -> new LocalMutation(MutationOutcome.SUCCESS_SCANNED, normalized.withScan(userId));
                case SCANNED -> normalized.scannedUserId() != null && normalized.scannedUserId().equals(userId)
                        ? new LocalMutation(MutationOutcome.SUCCESS_SCANNED, normalized)
                        : new LocalMutation(MutationOutcome.OWNER_CONFLICT, normalized);
                case CANCELLED -> new LocalMutation(MutationOutcome.CANCELLED, normalized);
                case CONFIRMED -> new LocalMutation(MutationOutcome.CONFIRMED, normalized);
                case EXPIRED -> new LocalMutation(MutationOutcome.EXPIRED, normalized);
            };
        }

        if (operation == MutationOperation.CONFIRM) {
            return switch (normalized.status()) {
                case PENDING -> userExists
                        ? new LocalMutation(MutationOutcome.SUCCESS_CONFIRMED, normalized.withConfirm(userId))
                        : new LocalMutation(MutationOutcome.USER_MISSING, normalized);
                case SCANNED -> {
                    if (normalized.scannedUserId() == null || !normalized.scannedUserId().equals(userId)) {
                        yield new LocalMutation(MutationOutcome.OWNER_CONFLICT, normalized);
                    }
                    yield userExists
                            ? new LocalMutation(MutationOutcome.SUCCESS_CONFIRMED, normalized.withConfirm(userId))
                            : new LocalMutation(MutationOutcome.USER_MISSING, normalized);
                }
                case CONFIRMED -> normalized.confirmedUserId() != null && normalized.confirmedUserId().equals(userId)
                        ? new LocalMutation(MutationOutcome.SUCCESS_CONFIRMED, normalized)
                        : new LocalMutation(MutationOutcome.OWNER_CONFLICT, normalized);
                case CANCELLED -> new LocalMutation(MutationOutcome.CANCELLED, normalized);
                case EXPIRED -> new LocalMutation(MutationOutcome.EXPIRED, normalized);
            };
        }

        return switch (normalized.status()) {
            case PENDING, SCANNED -> new LocalMutation(MutationOutcome.SUCCESS_CANCELLED, normalized.withStatus(QrLoginStatus.CANCELLED));
            case CANCELLED -> new LocalMutation(MutationOutcome.CANCELLED, normalized);
            case CONFIRMED -> new LocalMutation(MutationOutcome.CONFIRMED, normalized);
            case EXPIRED -> new LocalMutation(MutationOutcome.EXPIRED, normalized);
        };
    }

    private boolean isExpirable(QrLoginStatus status) {
        return status == QrLoginStatus.PENDING || status == QrLoginStatus.SCANNED;
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
                toUserDto(user)
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

    private UserDto toUserDto(User user) {
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
        if (qrId == null || qrId.isBlank()) {
            return null;
        }
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
            payload.put("expiresAtEpochMillis", String.valueOf(session.expiresAt().toInstant().toEpochMilli()));
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

    private enum MutationOperation {
        SCAN,
        CONFIRM,
        CANCEL
    }

    private enum MutationOutcome {
        SUCCESS_SCANNED,
        SUCCESS_CONFIRMED,
        SUCCESS_CANCELLED,
        MISSING,
        EXPIRED,
        CANCELLED,
        CONFIRMED,
        OWNER_CONFLICT,
        INVALID_TOKEN,
        USER_MISSING,
        INVALID
    }

    private record LocalMutation(MutationOutcome outcome, InMemoryQrLoginSession session) {
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
