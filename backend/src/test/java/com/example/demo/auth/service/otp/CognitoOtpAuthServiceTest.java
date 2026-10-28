package com.example.demo.auth.service.otp;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.AuthCodeChannel;
import com.example.demo.auth.dto.AuthCodeFinishRequest;
import com.example.demo.auth.dto.AuthCodeStartRequest;
import com.example.demo.auth.dto.AuthCodeStartResponse;
import com.example.demo.auth.dto.CognitoAuthFlowResponse;
import com.example.demo.auth.dto.CognitoAuthenticationResult;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CognitoOtpAuthServiceTest {

    private CognitoOtpAuthService service(CognitoOtpClient client, UserRepository users) {
        return new CognitoOtpAuthService(client, users, mock(UserProfileRepository.class),
                mock(PasswordEncoder.class), mock(JwtTokenProvider.class),
                AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class)), 300, 300, 60);
    }

    @Test
    void emailStartMustActuallyReturnTheRequestedProviderChallenge() {
        var client = mock(CognitoOtpClient.class);
        var users = mock(UserRepository.class);
        var wrongChallenge = new CognitoAuthFlowResponse();
        wrongChallenge.setChallengeName("PASSWORD"); wrongChallenge.setSession("session");
        when(client.initiateUserAuth("demo@example.com", "EMAIL_OTP")).thenReturn(wrongChallenge);
        var request = new AuthCodeStartRequest(); request.setChannel(AuthCodeChannel.email); request.setAddress("demo@example.com");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(client, users).start(request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(users);
    }

    @Test
    void emailFinishCannotProvisionAUserWithoutSuccessfulProviderAuthentication() {
        var client = mock(CognitoOtpClient.class);
        var users = mock(UserRepository.class);
        when(client.respondToChallenge(eq("EMAIL_OTP"), any(), any(), any())).thenReturn(new CognitoAuthFlowResponse());
        var request = new AuthCodeFinishRequest(); request.setChannel(AuthCodeChannel.email);
        request.setAddress("demo@example.com"); request.setSession("session"); request.setCode("123456");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(client, users).finish(request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(users);
    }

    @Test
    void emailFinishMapsOnlyClassifiedInvalidCodeToExistingUnauthorizedResponse() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        when(client.respondToChallenge(eq("EMAIL_OTP"), eq("invalid@example.com"), any(), eq("session")))
                .thenThrow(CognitoOtpProviderException.invalidCode());

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(client, users).finish(finishRequest(
                        AuthCodeChannel.email, "invalid@example.com", "session")));

        assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatusCode());
        assertEquals("邮箱验证码无效或已过期", thrown.getReason());
        assertEquals(null, thrown.getCause());
        verifyNoInteractions(users);
    }

    @Test
    void shouldStartEmailOtpLogin() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository userRepository = mock(UserRepository.class);
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        AuthVerifyService authVerifyService = AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class));
        CognitoAuthFlowResponse challenge = new CognitoAuthFlowResponse();
        challenge.setChallengeName("EMAIL_OTP");
        challenge.setSession("cognito-email-session");
        when(client.initiateUserAuth("demo@example.com", "EMAIL_OTP")).thenReturn(challenge);

        CognitoOtpAuthService service = new CognitoOtpAuthService(
                client,
                userRepository,
                userProfileRepository,
                passwordEncoder,
                jwtTokenProvider,
                authVerifyService,
                300,
                300,
                60
        );

        AuthCodeStartRequest request = new AuthCodeStartRequest();
        request.setChannel(AuthCodeChannel.email);
        request.setAddress("demo@example.com");

        AuthCodeStartResponse startResponse = service.start(request);

        assertEquals(AuthCodeChannel.email, startResponse.getChannel());
        assertEquals("cognito-email-session", startResponse.getSession());
        assertEquals(300, startResponse.getExpireSeconds());
        assertEquals(60, startResponse.getCooldownSeconds());
        assertTrue(startResponse.getMaskedAddress().contains("****"));
    }

    @Test
    void emailStartMapsProviderFailureToServiceUnavailableWithoutCauseOrLocalUserWrites() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        RuntimeException providerFailure = new IllegalStateException(
                "CognitoClientException access-key-secret endpoint-internal-details");
        when(client.initiateUserAuth("unavailable@example.com", "EMAIL_OTP"))
                .thenThrow(providerFailure);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(client, users).start(startRequest(
                        AuthCodeChannel.email, "unavailable@example.com")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatusCode());
        assertEquals("邮箱验证码服务暂不可用，请稍后重试", thrown.getReason());
        assertEquals(null, thrown.getCause());
        verifyNoInteractions(users);
    }

    @Test
    void phoneStartMapsProviderFailureToServiceUnavailableWithoutCauseOrLocalUserWrites() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        RuntimeException providerFailure = new IllegalStateException(
                "CognitoSmsException phone-number-secret provider-response-body");
        when(client.initiateUserAuth("13800138011", "SMS_OTP"))
                .thenThrow(providerFailure);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(client, users).start(startRequest(
                        AuthCodeChannel.phone, "13800138011")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatusCode());
        assertEquals("手机验证码服务暂不可用，请稍后重试", thrown.getReason());
        assertEquals(null, thrown.getCause());
        verifyNoInteractions(users);
    }

    @Test
    void shouldFinishEmailOtpLoginAndCreateLocalUser() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository userRepository = mock(UserRepository.class);
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        AuthVerifyService authVerifyService = AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class));
        CognitoAuthFlowResponse verified = new CognitoAuthFlowResponse();
        CognitoAuthenticationResult result = new CognitoAuthenticationResult();
        result.setAccessToken("cognito-email-token");
        verified.setAuthenticationResult(result);
        when(client.respondToChallenge(eq("EMAIL_OTP"), eq("demo@example.com"),
                eq(java.util.Map.of("EMAIL_OTP_CODE", "123456")), eq("session-1"))).thenReturn(verified);

        when(userRepository.findByEmail("demo@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUsername("demo")).thenReturn(false);
        when(userRepository.existsByPhone(any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(501L);
            return user;
        });
        when(userProfileRepository.findById(501L)).thenReturn(Optional.empty());
        when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtTokenProvider.generateToken(501L, "demo")).thenReturn("project-jwt");

        CognitoOtpAuthService service = new CognitoOtpAuthService(
                client,
                userRepository,
                userProfileRepository,
                passwordEncoder,
                jwtTokenProvider,
                authVerifyService,
                300,
                300,
                60
        );

        AuthCodeFinishRequest request = new AuthCodeFinishRequest();
        request.setChannel(AuthCodeChannel.email);
        request.setAddress("demo@example.com");
        request.setCode("123456");
        request.setSession("session-1");

        LoginResponse loginResponse = service.finish(request);

        assertEquals("project-jwt", loginResponse.getAccessToken());
        assertEquals("demo", loginResponse.getUser().getUsername());
        assertEquals("demo@example.com", loginResponse.getUser().getEmail());
        assertEquals(501L, loginResponse.getUser().getId());

        var created = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(created.capture());
        assertTrue(created.getValue().isEmailVerified());
        assertFalse(created.getValue().isPhoneVerified());
    }

    @Test
    void emailFinishRejectsAnUnverifiedExistingAssociationWithoutChangingIt() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        User existing = user(701L, "reserved@example.com", "13800138001", false, true);
        when(client.respondToChallenge(eq("EMAIL_OTP"), eq("reserved@example.com"), any(), eq("session")))
                .thenReturn(authenticated("cognito-access"));
        when(users.findByEmail("reserved@example.com")).thenReturn(Optional.of(existing));

        var request = finishRequest(AuthCodeChannel.email, "reserved@example.com", "session");
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> new CognitoOtpAuthService(client, users, mock(UserProfileRepository.class), passwords, tokens,
                        AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class)), 300, 300, 60)
                        .finish(request));

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertFalse(existing.isEmailVerified());
        verify(users, never()).save(any(User.class));
        verify(passwords, never()).encode(anyString());
        verify(tokens, never()).generateToken(anyLong(), anyString());
    }

    @Test
    void verifiedEmailAssociationCanLoginWithoutProvisioningOrMutation() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        User existing = user(703L, "verified@example.com", "13800138003", true, false);
        when(client.respondToChallenge(eq("EMAIL_OTP"), eq("verified@example.com"), any(), eq("session")))
                .thenReturn(authenticated("cognito-access"));
        when(users.findByEmail("verified@example.com")).thenReturn(Optional.of(existing));
        when(tokens.generateToken(703L, "verified")).thenReturn("project-jwt");

        var service = new CognitoOtpAuthService(client, users, mock(UserProfileRepository.class), mock(PasswordEncoder.class),
                tokens, AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class)), 300, 300, 60);
        LoginResponse response = service.finish(finishRequest(AuthCodeChannel.email, "verified@example.com", "session"));

        assertEquals("project-jwt", response.getAccessToken());
        assertTrue(existing.isEmailVerified());
        verify(users, never()).save(any(User.class));
    }

    @Test
    void phoneFinishRejectsAnUnverifiedExistingAssociationWithoutChangingIt() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        User existing = user(704L, "reserved-phone@example.com", "13800138004", true, false);
        when(client.respondToChallenge(eq("SMS_OTP"), eq("13800138004"), any(), eq("session")))
                .thenReturn(authenticated("cognito-access"));
        when(users.findByPhone("13800138004")).thenReturn(Optional.of(existing));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> new CognitoOtpAuthService(client, users, mock(UserProfileRepository.class), passwords, tokens,
                        AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class)), 300, 300, 60)
                        .finish(finishRequest(AuthCodeChannel.phone, "13800138004", "session")));

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertFalse(existing.isPhoneVerified());
        verify(users, never()).save(any(User.class));
        verify(passwords, never()).encode(anyString());
        verify(tokens, never()).generateToken(anyLong(), anyString());
    }

    @Test
    void shouldStartPhoneOtpLogin() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        AuthVerifyService authVerifyService = AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class));

        CognitoAuthFlowResponse response = new CognitoAuthFlowResponse();
        response.setChallengeName("SMS_OTP");
        response.setSession("cognito-session-phone");
        when(client.initiateUserAuth("13800138000", "SMS_OTP")).thenReturn(response);

        CognitoOtpAuthService service = new CognitoOtpAuthService(
                client,
                mock(UserRepository.class),
                mock(UserProfileRepository.class),
                mock(PasswordEncoder.class),
                mock(JwtTokenProvider.class),
                authVerifyService,
                300,
                300,
                60
        );

        AuthCodeStartRequest request = new AuthCodeStartRequest();
        request.setChannel(AuthCodeChannel.phone);
        request.setAddress("13800138000");

        AuthCodeStartResponse startResponse = service.start(request);

        assertEquals(AuthCodeChannel.phone, startResponse.getChannel());
        assertEquals("cognito-session-phone", startResponse.getSession());
        assertEquals(300, startResponse.getExpireSeconds());
        assertEquals("138****8000", startResponse.getMaskedAddress());
    }

    @Test
    void shouldFinishPhoneOtpLoginAndCreateLocalUser() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository userRepository = mock(UserRepository.class);
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        AuthVerifyService authVerifyService = AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class));

        CognitoAuthFlowResponse response = new CognitoAuthFlowResponse();
        CognitoAuthenticationResult authResult = new CognitoAuthenticationResult();
        authResult.setAccessToken("cognito-access");
        response.setAuthenticationResult(authResult);
        when(client.respondToChallenge(eq("SMS_OTP"), eq("13800138000"), any(), eq("session-phone"))).thenReturn(response);

        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.empty());
        when(userRepository.existsByUsername("13800138000_ph")).thenReturn(false);
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(601L);
            return user;
        });
        when(userProfileRepository.findById(601L)).thenReturn(Optional.empty());
        when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtTokenProvider.generateToken(601L, "13800138000_ph")).thenReturn("phone-project-jwt");

        CognitoOtpAuthService service = new CognitoOtpAuthService(
                client,
                userRepository,
                userProfileRepository,
                passwordEncoder,
                jwtTokenProvider,
                authVerifyService,
                300,
                300,
                60
        );

        AuthCodeFinishRequest request = new AuthCodeFinishRequest();
        request.setChannel(AuthCodeChannel.phone);
        request.setAddress("13800138000");
        request.setCode("123456");
        request.setSession("session-phone");

        LoginResponse loginResponse = service.finish(request);

        assertEquals("phone-project-jwt", loginResponse.getAccessToken());
        assertEquals("13800138000_ph", loginResponse.getUser().getUsername());
        assertEquals("13800138000", loginResponse.getUser().getPhone());
        assertEquals(601L, loginResponse.getUser().getId());

        var created = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(created.capture());
        assertFalse(created.getValue().isEmailVerified());
        assertTrue(created.getValue().isPhoneVerified());
    }

    @Test
    void phoneFinishRejectsNullCognitoResponseBeforeLocalLookup() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        when(client.respondToChallenge(eq("SMS_OTP"), eq("13800138000"), any(), eq("session")))
                .thenReturn(null);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> new CognitoOtpAuthService(client, users, mock(UserProfileRepository.class), mock(PasswordEncoder.class),
                        mock(JwtTokenProvider.class), AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class)),
                        300, 300, 60).finish(finishRequest(AuthCodeChannel.phone, "13800138000", "session")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatusCode());
        assertEquals("手机验证码服务暂不可用，请稍后重试", thrown.getReason());
        assertEquals(null, thrown.getCause());
        verify(users, never()).findByPhone(anyString());
    }

    @Test
    void emailFinishRejectsBlankAccessTokenBeforeLocalLookup() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        when(client.respondToChallenge(eq("EMAIL_OTP"), eq("blank@example.com"), any(), eq("session")))
                .thenReturn(authenticated("   "));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> new CognitoOtpAuthService(client, users, mock(UserProfileRepository.class), mock(PasswordEncoder.class),
                        mock(JwtTokenProvider.class), AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class)),
                        300, 300, 60).finish(finishRequest(AuthCodeChannel.email, "blank@example.com", "session")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatusCode());
        assertEquals("邮箱验证码服务暂不可用，请稍后重试", thrown.getReason());
        assertEquals(null, thrown.getCause());
        verify(users, never()).findByEmail(anyString());
    }

    @Test
    void emailFinishMapsUnknownProviderFailureToServiceUnavailableWithoutCauseOrProvisioning() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        RuntimeException providerFailure = new IllegalStateException(
                "CodeMismatchException provider-body-secret session-secret");
        when(client.respondToChallenge(eq("EMAIL_OTP"), eq("unavailable@example.com"), any(), eq("session")))
                .thenThrow(providerFailure);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(client, users).finish(
                        finishRequest(AuthCodeChannel.email, "unavailable@example.com", "session")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatusCode());
        assertEquals("邮箱验证码服务暂不可用，请稍后重试", thrown.getReason());
        assertEquals(null, thrown.getCause());
        verifyNoInteractions(users);
    }

    @Test
    void phoneFinishMapsUnknownProviderFailureToServiceUnavailableWithoutCauseOrProvisioning() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        RuntimeException providerFailure = new IllegalStateException(
                "ExpiredCodeException provider-body-secret session-secret");
        when(client.respondToChallenge(eq("SMS_OTP"), eq("13800138009"), any(), eq("session")))
                .thenThrow(providerFailure);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(client, users).finish(
                        finishRequest(AuthCodeChannel.phone, "13800138009", "session")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatusCode());
        assertEquals("手机验证码服务暂不可用，请稍后重试", thrown.getReason());
        assertEquals(null, thrown.getCause());
        verifyNoInteractions(users);
    }

    @Test
    void phoneFinishMapsOnlyClassifiedInvalidCodeToExistingUnauthorizedResponse() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        when(client.respondToChallenge(eq("SMS_OTP"), eq("13800138010"), any(), eq("session")))
                .thenThrow(CognitoOtpProviderException.invalidCode());

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(client, users).finish(finishRequest(
                        AuthCodeChannel.phone, "13800138010", "session")));

        assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatusCode());
        assertEquals("手机验证码无效、已过期或 Cognito 校验失败", thrown.getReason());
        assertEquals(null, thrown.getCause());
        verifyNoInteractions(users);
    }

    @Test
    void verifiedPhoneAssociationCanLoginWithoutProvisioningOrMutation() {
        CognitoOtpClient client = mock(CognitoOtpClient.class);
        UserRepository users = mock(UserRepository.class);
        JwtTokenProvider tokens = mock(JwtTokenProvider.class);
        User existing = user(702L, "verified@example.com", "13800138002", false, true);
        when(client.respondToChallenge(eq("SMS_OTP"), eq("13800138002"), any(), eq("session")))
                .thenReturn(authenticated("cognito-access"));
        when(users.findByPhone("13800138002")).thenReturn(Optional.of(existing));
        when(tokens.generateToken(702L, "verified")).thenReturn("project-jwt");

        var service = new CognitoOtpAuthService(client, users, mock(UserProfileRepository.class), mock(PasswordEncoder.class),
                tokens, AuthVerifyService.forTest("off", "", mock(TurnstileVerifyClient.class)), 300, 300, 60);
        LoginResponse response = service.finish(finishRequest(AuthCodeChannel.phone, "13800138002", "session"));

        assertEquals("project-jwt", response.getAccessToken());
        assertTrue(existing.isPhoneVerified());
        verify(users, never()).save(any(User.class));
    }

    private static CognitoAuthFlowResponse authenticated(String accessToken) {
        CognitoAuthFlowResponse response = new CognitoAuthFlowResponse();
        CognitoAuthenticationResult result = new CognitoAuthenticationResult();
        result.setAccessToken(accessToken);
        response.setAuthenticationResult(result);
        return response;
    }

    private static AuthCodeFinishRequest finishRequest(AuthCodeChannel channel, String address, String session) {
        AuthCodeFinishRequest request = new AuthCodeFinishRequest();
        request.setChannel(channel);
        request.setAddress(address);
        request.setCode("123456");
        request.setSession(session);
        return request;
    }

    private static AuthCodeStartRequest startRequest(AuthCodeChannel channel, String address) {
        AuthCodeStartRequest request = new AuthCodeStartRequest();
        request.setChannel(channel);
        request.setAddress(address);
        return request;
    }

    private static User user(Long id, String email, String phone, boolean emailVerified, boolean phoneVerified) {
        User user = new User();
        user.setId(id);
        user.setUsername("verified");
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash("existing-password");
        user.setEmailVerified(emailVerified);
        user.setPhoneVerified(phoneVerified);
        return user;
    }
}
