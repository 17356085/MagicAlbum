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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
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
    }
}
