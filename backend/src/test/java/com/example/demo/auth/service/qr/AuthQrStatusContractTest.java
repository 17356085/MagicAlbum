package com.example.demo.auth.service.qr;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.QrLoginCreateResponse;
import com.example.demo.auth.dto.QrLoginStatus;
import com.example.demo.auth.dto.QrLoginStatusResponse;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthQrStatusContractTest {
    private static final Set<String> STATUS_FIELDS = Set.of("qrId", "qrUrl", "expiresAt", "status", "message");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void statusResponsesSerializeOnlyTheMinimalStatusContract() throws Exception {
        UserRepository userRepository = mockUserRepository();
        AuthQrService service = new AuthQrService(userRepository, mockJwtTokenProvider(), 60, "magicalbum://auth/qr/");

        QrLoginCreateResponse pendingSession = service.createSession();
        assertStatusFields(service.getStatus(pendingSession.getQrId(), pendingSession.getPollToken()), QrLoginStatus.PENDING);

        QrLoginCreateResponse scannedSession = service.createSession();
        assertStatusFields(service.scan(scannedSession.getQrId(), 1001L), QrLoginStatus.SCANNED);

        QrLoginCreateResponse confirmedSession = service.createSession();
        service.scan(confirmedSession.getQrId(), 1001L);
        assertStatusFields(service.confirm(confirmedSession.getQrId(), 1001L), QrLoginStatus.CONFIRMED);

        QrLoginCreateResponse cancelledSession = service.createSession();
        service.cancel(cancelledSession.getQrId(), cancelledSession.getPollToken());
        assertStatusFields(service.getStatus(cancelledSession.getQrId(), cancelledSession.getPollToken()), QrLoginStatus.CANCELLED);
    }

    @Test
    void confirmedStatusPollingDoesNotReadUserRepository() throws Exception {
        UserRepository userRepository = mockUserRepository();
        AuthQrService service = new AuthQrService(userRepository, mockJwtTokenProvider(), 60, "magicalbum://auth/qr/");

        QrLoginCreateResponse created = service.createSession();
        service.scan(created.getQrId(), 1001L);
        service.confirm(created.getQrId(), 1001L);
        clearInvocations(userRepository);

        QrLoginStatusResponse status = service.getStatus(created.getQrId(), created.getPollToken());

        assertEquals(QrLoginStatus.CONFIRMED, status.getStatus());
        verifyNoInteractions(userRepository);
    }

    @Test
    void exchangeStillReturnsTheCompleteUserAndJwtContract() {
        UserRepository userRepository = mockUserRepository();
        JwtTokenProvider tokenProvider = mockJwtTokenProvider();
        AuthQrService service = new AuthQrService(userRepository, tokenProvider, 60, "magicalbum://auth/qr/");

        QrLoginCreateResponse created = service.createSession();
        service.scan(created.getQrId(), 1001L);
        service.confirm(created.getQrId(), 1001L);

        var login = service.exchange(created.getQrId(), created.getPollToken());

        assertEquals("qr-login-token", login.getAccessToken());
        assertEquals(1001L, login.getUser().getId());
        assertEquals("qr_user@example.test", login.getUser().getEmail());
        assertEquals("+85255550123", login.getUser().getPhone());
    }

    @Test
    void exchangeUsesOneRepositorySnapshotForJwtAndUserDtoInMemory() {
        UserRepository userRepository = mock(UserRepository.class);
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        User setupUser = user(1001L, "setup-user", "setup@example.test", "+85200000000", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        User snapshotA = user(1001L, "snapshot-a", "a@example.test", "+85211111111", OffsetDateTime.parse("2026-02-02T00:00:00Z"));
        User snapshotB = user(1001L, "snapshot-b", "b@example.test", "+85222222222", OffsetDateTime.parse("2026-03-03T00:00:00Z"));
        when(userRepository.findById(1001L)).thenReturn(Optional.of(setupUser));
        AuthQrService service = new AuthQrService(userRepository, tokenProvider, 60, "magicalbum://auth/qr/");

        QrLoginCreateResponse created = service.createSession();
        service.scan(created.getQrId(), 1001L);
        service.confirm(created.getQrId(), 1001L);

        clearInvocations(userRepository, tokenProvider);
        when(userRepository.findById(1001L)).thenReturn(Optional.of(snapshotA), Optional.of(snapshotB));
        when(tokenProvider.generateToken(1001L, "snapshot-a")).thenReturn("snapshot-a-token");
        clearInvocations(userRepository, tokenProvider);

        var login = service.exchange(created.getQrId(), created.getPollToken());

        assertEquals("snapshot-a-token", login.getAccessToken());
        assertEquals(snapshotA.getId(), login.getUser().getId());
        assertEquals(snapshotA.getUsername(), login.getUser().getUsername());
        assertEquals(snapshotA.getEmail(), login.getUser().getEmail());
        assertEquals(snapshotA.getPhone(), login.getUser().getPhone());
        assertEquals(snapshotA.getCreatedAt(), login.getUser().getCreatedAt());
        verify(userRepository, times(1)).findById(1001L);
        verify(tokenProvider).generateToken(snapshotA.getId(), snapshotA.getUsername());
    }

    @Test
    void exchangeReturnsNotFoundAndDoesNotSignJwtWhenUserDisappears() {
        UserRepository userRepository = mockUserRepository();
        JwtTokenProvider tokenProvider = mockJwtTokenProvider();
        AuthQrService service = new AuthQrService(userRepository, tokenProvider, 60, "magicalbum://auth/qr/");

        QrLoginCreateResponse created = service.createSession();
        service.scan(created.getQrId(), 1001L);
        service.confirm(created.getQrId(), 1001L);
        clearInvocations(userRepository, tokenProvider);
        when(userRepository.findById(1001L)).thenReturn(Optional.empty());
        clearInvocations(userRepository, tokenProvider);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.exchange(created.getQrId(), created.getPollToken())
        );

        assertEquals(404, failure.getStatusCode().value());
        verify(userRepository, times(1)).findById(1001L);
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void redisExchangeUsesTheSameIssueLoginSnapshotPath() {
        UserRepository userRepository = mockUserRepository();
        JwtTokenProvider tokenProvider = mockJwtTokenProvider();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn("1001");
        AuthQrService service = new AuthQrService(userRepository, tokenProvider, 60, "magicalbum://auth/qr/", redisTemplate);

        clearInvocations(userRepository, tokenProvider);
        var login = service.exchange("qr-id", "poll-token");

        assertEquals("qr-login-token", login.getAccessToken());
        assertEquals(1001L, login.getUser().getId());
        verify(userRepository, times(1)).findById(1001L);
        verify(tokenProvider).generateToken(1001L, "qr_user");
    }

    @Test
    void expiredStatusAlsoUsesOnlyTheMinimalContract() throws Exception {
        AuthQrService service = new AuthQrService(mockUserRepository(), mockJwtTokenProvider(), 1, "magicalbum://auth/qr/");
        QrLoginCreateResponse created = service.createSession();

        Thread.sleep(1100);

        assertStatusFields(service.getStatus(created.getQrId(), created.getPollToken()), QrLoginStatus.EXPIRED);
    }

    private void assertStatusFields(QrLoginStatusResponse response, QrLoginStatus expectedStatus) throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));
        Set<String> actualFields = new HashSet<>();
        json.fieldNames().forEachRemaining(actualFields::add);
        assertEquals(STATUS_FIELDS, actualFields);
        assertEquals(expectedStatus.name(), json.get("status").asText());
        assertFalse(json.has("user"));
        assertTrue(actualFields.stream().noneMatch(field -> field.toLowerCase().contains("token")));
    }

    private UserRepository mockUserRepository() {
        UserRepository repository = mock(UserRepository.class);
        User user = user(1001L, "qr_user", "qr_user@example.test", "+85255550123", OffsetDateTime.now());
        when(repository.findById(1001L)).thenReturn(Optional.of(user));
        return repository;
    }

    private User user(Long id, String username, String email, String phone, OffsetDateTime createdAt) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setCreatedAt(createdAt);
        return user;
    }

    private JwtTokenProvider mockJwtTokenProvider() {
        JwtTokenProvider provider = mock(JwtTokenProvider.class);
        when(provider.generateToken(1001L, "qr_user")).thenReturn("qr-login-token");
        return provider;
    }
}
