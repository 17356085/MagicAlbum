package com.example.demo.auth.service.qr;

import com.example.demo.auth.dto.QrLoginCreateResponse;
import com.example.demo.auth.dto.QrLoginStatus;
import com.example.demo.auth.dto.QrLoginStatusResponse;
import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthQrServiceTest {

    private AuthQrService createService() {
        return new AuthQrService(mockUserRepository(), mockJwtTokenProvider(), 60, "magicalbum://auth/qr/");
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

    @Test
    void shouldCreateSession() {
        AuthQrService service = createService();
        QrLoginCreateResponse response = service.createSession();

        assertNotNull(response.getQrId());
        assertTrue(response.getQrUrl().contains(response.getQrId()));
        assertEquals(QrLoginStatus.PENDING, response.getStatus());
        assertNotNull(response.getExpiresAt());
    }

    @Test
    void shouldReturnPendingStatusForFreshSession() {
        AuthQrService service = createService();
        QrLoginCreateResponse created = service.createSession();

        QrLoginStatusResponse response = service.getStatus(created.getQrId());

        assertEquals(QrLoginStatus.PENDING, response.getStatus());
        assertEquals("等待扫码", response.getMessage());
    }

    @Test
    void shouldCancelSession() {
        AuthQrService service = createService();
        QrLoginCreateResponse created = service.createSession();

        service.cancel(created.getQrId());
        QrLoginStatusResponse response = service.getStatus(created.getQrId());

        assertEquals(QrLoginStatus.CANCELED, response.getStatus());
        assertEquals("二维码登录已取消", response.getMessage());
    }

    @Test
    void shouldMarkSessionExpiredWhenTtlElapsed() throws InterruptedException {
        AuthQrService service = new AuthQrService(mockUserRepository(), mockJwtTokenProvider(), 30, "magicalbum://auth/qr/");
        QrLoginCreateResponse created = service.createSession();

        Thread.sleep(1100);

        // Use a dedicated tiny-TTL service instance to exercise expiry behavior.
        AuthQrService tinyTtlService = new AuthQrService(mockUserRepository(), mockJwtTokenProvider(), 1, "magicalbum://auth/qr/");
        QrLoginCreateResponse tiny = tinyTtlService.createSession();
        Thread.sleep(1200);
        QrLoginStatusResponse expired = tinyTtlService.getStatus(tiny.getQrId());

        assertEquals(QrLoginStatus.EXPIRED, expired.getStatus());
        assertEquals("二维码已过期，请刷新", expired.getMessage());

        // sanity check the original created session is still structurally valid
        assertNotNull(created.getQrId());
    }

    @Test
    void shouldThrowWhenSessionMissing() {
        AuthQrService service = createService();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.getStatus("missing"));

        assertEquals(404, ex.getStatusCode().value());
        assertEquals("二维码会话不存在或已失效", ex.getReason());
    }

    @Test
    void shouldScanAndConfirmSession() {
        AuthQrService service = createService();
        QrLoginCreateResponse created = service.createSession();

        QrLoginStatusResponse scanned = service.scan(created.getQrId(), 1001L);
        assertEquals(QrLoginStatus.SCANNED, scanned.getStatus());
        assertEquals("已扫码，请在移动端确认登录", scanned.getMessage());

        QrLoginStatusResponse confirmed = service.confirm(created.getQrId(), 1001L);
        assertEquals(QrLoginStatus.CONFIRMED, confirmed.getStatus());
        assertEquals("qr-login-token", confirmed.getAccessToken());
        assertNotNull(confirmed.getUser());
        assertEquals(1001L, confirmed.getUser().getId());
    }
}
