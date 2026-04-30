package com.example.demo.auth.service.qr;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.QrLoginCreateResponse;
import com.example.demo.auth.dto.QrLoginStatus;
import com.example.demo.auth.dto.QrLoginStatusResponse;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthQrServiceRedisTest {

    @Test
    void shouldStoreSessionInRedisAndReadItBack() {
        UserRepository userRepository = mockUserRepository();
        JwtTokenProvider jwtTokenProvider = mockJwtTokenProvider();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        AuthQrService service = new AuthQrService(userRepository, jwtTokenProvider, 60, "magicalbum://auth/qr/", redisTemplate);
        QrLoginCreateResponse created = service.createSession();

        Map<Object, Object> stored = Map.of(
                "qrId", created.getQrId(),
                "qrUrl", created.getQrUrl(),
                "status", QrLoginStatus.PENDING.name(),
                "createdAt", OffsetDateTime.now().toString(),
                "expiresAt", created.getExpiresAt().toString(),
                "scannedUserId", "",
                "confirmedUserId", "",
                "accessToken", ""
        );
        when(hashOperations.entries("auth:qr:" + created.getQrId())).thenReturn(stored);

        QrLoginStatusResponse response = service.getStatus(created.getQrId());

        assertEquals(QrLoginStatus.PENDING, response.getStatus());
        assertEquals("等待扫码", response.getMessage());
        verify(hashOperations).putAll(anyString(), any(Map.class));
        verify(redisTemplate).expire(anyString(), any(Duration.class));
    }

    @Test
    void shouldFallbackToInMemoryWhenRedisUnavailable() {
        UserRepository userRepository = mockUserRepository();
        JwtTokenProvider jwtTokenProvider = mockJwtTokenProvider();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForHash()).thenThrow(new RuntimeException("redis down"));

        AuthQrService service = new AuthQrService(userRepository, jwtTokenProvider, 60, "magicalbum://auth/qr/", redisTemplate);
        QrLoginCreateResponse created = service.createSession();
        QrLoginStatusResponse scanned = service.scan(created.getQrId(), 1001L);
        QrLoginStatusResponse confirmed = service.confirm(created.getQrId(), 1001L);

        assertEquals(QrLoginStatus.SCANNED, scanned.getStatus());
        assertEquals(QrLoginStatus.CONFIRMED, confirmed.getStatus());
        assertNotNull(confirmed.getAccessToken());
    }

    private UserRepository mockUserRepository() {
        UserRepository repository = mock(UserRepository.class);
        User user = new User();
        user.setId(1001L);
        user.setUsername("qr_user");
        user.setEmail("qr@example.com");
        user.setPhone("13800138000");
        user.setCreatedAt(OffsetDateTime.now());
        when(repository.findById(1001L)).thenReturn(Optional.of(user));
        return repository;
    }

    private JwtTokenProvider mockJwtTokenProvider() {
        JwtTokenProvider provider = mock(JwtTokenProvider.class);
        when(provider.generateToken(1001L, "qr_user")).thenReturn("qr-login-token");
        return provider;
    }
}
