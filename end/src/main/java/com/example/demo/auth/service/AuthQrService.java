package com.example.demo.auth.service;

import com.example.demo.auth.dto.QrLoginCreateResponse;
import com.example.demo.auth.dto.QrLoginStatus;
import com.example.demo.auth.dto.QrLoginStatusResponse;
import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthQrService {
    private final long ttlSeconds;
    private final String qrUrlPrefix;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final Map<String, InMemoryQrLoginSession> sessions = new ConcurrentHashMap<>();

    public AuthQrService(
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            @Value("${app.auth.qr.ttl-seconds:60}") long ttlSeconds,
            @Value("${app.auth.qr.url-prefix:magicalbum://auth/qr/}") String qrUrlPrefix
    ) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.ttlSeconds = Math.max(ttlSeconds, 1);
        this.qrUrlPrefix = (qrUrlPrefix == null || qrUrlPrefix.isBlank())
                ? "magicalbum://auth/qr/"
                : qrUrlPrefix;
    }

    public QrLoginCreateResponse createSession() {
        purgeExpiredSessions();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusSeconds(ttlSeconds);
        String qrId = UUID.randomUUID().toString().replace("-", "");

        InMemoryQrLoginSession session = new InMemoryQrLoginSession(
                qrId,
                buildQrUrl(qrId),
                QrLoginStatus.PENDING,
                now,
                expiresAt,
                null,
                null,
                null
        );
        sessions.put(qrId, session);

        QrLoginCreateResponse response = new QrLoginCreateResponse();
        response.setQrId(session.qrId());
        response.setQrUrl(session.qrUrl());
        response.setExpiresAt(session.expiresAt());
        response.setStatus(session.status());
        return response;
    }

    public QrLoginStatusResponse getStatus(String qrId) {
        InMemoryQrLoginSession session = sessions.get(qrId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }

        InMemoryQrLoginSession normalized = normalizeStatus(session);
        if (normalized != session) {
            sessions.put(qrId, normalized);
        }

        QrLoginStatusResponse response = new QrLoginStatusResponse();
        response.setQrId(normalized.qrId());
        response.setQrUrl(normalized.qrUrl());
        response.setExpiresAt(normalized.expiresAt());
        response.setStatus(normalized.status());
        response.setMessage(resolveMessage(normalized.status()));
        response.setAccessToken(normalized.accessToken());
        response.setUser(toUserDto(normalized.confirmedUserId()));
        return response;
    }

    public void cancel(String qrId) {
        InMemoryQrLoginSession session = sessions.get(qrId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }

        InMemoryQrLoginSession normalized = normalizeStatus(session);
        if (normalized.status() == QrLoginStatus.EXPIRED) {
            sessions.put(qrId, normalized);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已过期，无法取消");
        }
        if (normalized.status() == QrLoginStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已确认，无法取消");
        }
        if (normalized.status() == QrLoginStatus.CANCELED) {
            return;
        }

        sessions.put(qrId, normalized.withStatus(QrLoginStatus.CANCELED));
    }

    public QrLoginStatusResponse scan(String qrId, Long userId) {
        InMemoryQrLoginSession session = requireActiveSession(qrId);
        if (session.status() == QrLoginStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已确认，无法重复扫码");
        }
        if (session.status() == QrLoginStatus.CANCELED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "二维码已取消，无法扫码");
        }

        InMemoryQrLoginSession updated = session.withScan(userId);
        sessions.put(qrId, updated);
        return toStatusResponse(updated, "已扫码，请在移动端确认登录");
    }

    public QrLoginStatusResponse confirm(String qrId, Long userId) {
        InMemoryQrLoginSession session = requireActiveSession(qrId);
        if (session.status() == QrLoginStatus.CANCELED) {
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
        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getUsername());

        InMemoryQrLoginSession updated = session.withConfirm(userId, accessToken);
        sessions.put(qrId, updated);
        return toStatusResponse(updated, "已确认登录");
    }

    private void purgeExpiredSessions() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now.minusMinutes(5)));
    }

    private InMemoryQrLoginSession normalizeStatus(InMemoryQrLoginSession session) {
        if (session.status() == QrLoginStatus.CANCELED || session.status() == QrLoginStatus.CONFIRMED) {
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
            case CANCELED -> "二维码登录已取消";
            case PENDING -> "等待扫码";
        };
    }

    private InMemoryQrLoginSession requireActiveSession(String qrId) {
        InMemoryQrLoginSession session = sessions.get(qrId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "二维码会话不存在或已失效");
        }
        InMemoryQrLoginSession normalized = normalizeStatus(session);
        if (normalized != session) {
            sessions.put(qrId, normalized);
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
        response.setAccessToken(session.accessToken());
        response.setUser(toUserDto(session.confirmedUserId()));
        return response;
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

    private record InMemoryQrLoginSession(
            String qrId,
            String qrUrl,
            QrLoginStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            Long scannedUserId,
            Long confirmedUserId,
            String accessToken
    ) {
        private InMemoryQrLoginSession withStatus(QrLoginStatus nextStatus) {
            return new InMemoryQrLoginSession(qrId, qrUrl, nextStatus, createdAt, expiresAt, scannedUserId, confirmedUserId, accessToken);
        }

        private InMemoryQrLoginSession withScan(Long userId) {
            return new InMemoryQrLoginSession(qrId, qrUrl, QrLoginStatus.SCANNED, createdAt, expiresAt, userId, confirmedUserId, accessToken);
        }

        private InMemoryQrLoginSession withConfirm(Long userId, String nextAccessToken) {
            return new InMemoryQrLoginSession(qrId, qrUrl, QrLoginStatus.CONFIRMED, createdAt, expiresAt, userId, userId, nextAccessToken);
        }
    }
}
