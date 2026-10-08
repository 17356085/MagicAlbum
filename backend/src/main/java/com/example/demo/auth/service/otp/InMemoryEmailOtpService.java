package com.example.demo.auth.service.otp;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InMemoryEmailOtpService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, EmailOtpSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> latestSessionByEmail = new ConcurrentHashMap<>();

    public synchronized EmailOtpTicket start(String email, long expireSeconds, long cooldownSeconds) {
        Instant now = Instant.now();
        String existingSessionId = latestSessionByEmail.get(email);
        if (existingSessionId != null) {
            EmailOtpSession existing = sessions.get(existingSessionId);
            if (existing != null && existing.cooldownUntil().isAfter(now)) {
                long retryAfter = Math.max(1L, existing.cooldownUntil().getEpochSecond() - now.getEpochSecond());
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "验证码发送过于频繁，请 " + retryAfter + " 秒后再试");
            }
        }

        String sessionId = UUID.randomUUID().toString();
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        EmailOtpSession session = new EmailOtpSession(
                sessionId,
                email,
                hashCode(sessionId, code),
                now.plusSeconds(expireSeconds),
                now.plusSeconds(cooldownSeconds),
                0
        );
        sessions.put(sessionId, session);
        latestSessionByEmail.put(email, sessionId);

        return new EmailOtpTicket(sessionId, code);
    }

    public synchronized void verify(String email, String sessionId, String code) {
        Instant now = Instant.now();
        EmailOtpSession session = sessions.get(sessionId);
        if (session == null || !session.email().equalsIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱验证码会话无效");
        }
        if (session.expiresAt().isBefore(now)) {
            sessions.remove(sessionId);
            latestSessionByEmail.remove(email, sessionId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱验证码已过期，请重新获取");
        }
        if (!MessageDigest.isEqual(
                session.codeHash().getBytes(StandardCharsets.UTF_8),
                hashCode(session.sessionId(), code).getBytes(StandardCharsets.UTF_8)
        )) {
            int nextAttempts = session.failedAttempts() + 1;
            if (nextAttempts >= 5) {
                sessions.remove(sessionId);
                latestSessionByEmail.remove(email, sessionId);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱验证码错误次数过多，请重新获取");
            }
            sessions.put(sessionId, session.withFailedAttempts(nextAttempts));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱验证码不正确");
        }

        sessions.remove(sessionId);
        latestSessionByEmail.remove(email, sessionId);
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "****" + email.substring(Math.max(atIndex, 0));
        }
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        String prefix = local.substring(0, Math.min(2, local.length()));
        return prefix + "****" + domain;
    }

    public record EmailOtpTicket(String sessionId, String code) {
    }

    private record EmailOtpSession(
            String sessionId,
            String email,
            String codeHash,
            Instant expiresAt,
            Instant cooldownUntil,
            int failedAttempts
    ) {
        private EmailOtpSession withFailedAttempts(int nextFailedAttempts) {
            return new EmailOtpSession(sessionId, email, codeHash, expiresAt, cooldownUntil, nextFailedAttempts);
        }
    }

    private String hashCode(String sessionId, String code) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest((sessionId + ":" + (code == null ? "" : code.trim()))
                                    .getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
