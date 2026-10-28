package com.example.demo.user.service;

import com.example.demo.auth.service.otp.AuthVerifyService;
import com.example.demo.user.dto.RegisterRequest;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserRegistrationVerificationOrderTest {
    private UserRepository userRepository;
    private UserProfileRepository userProfileRepository;
    private PasswordEncoder passwordEncoder;
    private AuthVerifyService authVerifyService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userProfileRepository = mock(UserProfileRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authVerifyService = mock(AuthVerifyService.class);
        service = new UserService(userRepository, userProfileRepository, passwordEncoder, authVerifyService);
    }

    @Test
    void register_rejects_multibyte_password_over_72_utf8_bytes_before_any_side_effect() {
        String password = "密".repeat(25);
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(75);
        RegisterRequest request = requestWithPassword(password);

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(thrown -> {
                    ResponseStatusException exception = (ResponseStatusException) thrown;
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("密码过长，请减少字符数量");
                });

        verifyNoInteractions(authVerifyService, passwordEncoder, userRepository, userProfileRepository);
    }

    @Test
    void register_with_multibyte_password_at_72_utf8_bytes_continues_to_verification_and_persistence() {
        String password = "密".repeat(24);
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        RegisterRequest request = requestWithPassword(password);
        request.setVerifyToken("verification-token");
        request.setVerifyProvider("mock-manual");
        request.setVerifyScene("register");
        when(passwordEncoder.encode(password)).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.register(request);

        verify(authVerifyService).verify("verification-token", "mock-manual", "register", "register");
        verify(passwordEncoder).encode(password);
        verify(userRepository).existsByUsername(request.getUsername());
        verify(userRepository).existsByEmail(request.getEmail());
        verify(userRepository).existsByPhone(request.getPhone());
        verify(userRepository).save(any(User.class));
    }

    private static RegisterRequest requestWithPassword(String password) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("registration-order-user");
        request.setEmail("registration-order@example.test");
        request.setPhone("13800138000");
        request.setPassword(password);
        return request;
    }
}
