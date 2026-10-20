package com.example.demo.user.service;

import com.example.demo.auth.service.otp.AuthVerifyService;
import com.example.demo.user.dto.BasicInfoUpdateRequest;
import com.example.demo.user.dto.RegisterRequest;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServicePhoneConflictTest {
    private static final String PHONE = "13800138000";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        AuthVerifyService authVerifyService = mock(AuthVerifyService.class);
        service = new UserService(userRepository, userProfileRepository, passwordEncoder, authVerifyService);
    }

    @Test
    void registration_with_existing_phone_returns_conflict() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("phone-conflict-user");
        request.setEmail("phone-conflict@example.test");
        request.setPhone(PHONE);
        request.setPassword("Password1");
        when(userRepository.existsByPhone(PHONE)).thenReturn(true);

        Throwable thrown = catchThrowable(() -> service.register(request));

        assertPhoneConflict(thrown);
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void basic_info_update_with_another_users_phone_returns_conflict() {
        User current = user(7L, "current-user", "current@example.test", "13800138001");
        User other = user(8L, "other-user", "other@example.test", PHONE);
        BasicInfoUpdateRequest request = new BasicInfoUpdateRequest();
        request.setPhone(PHONE);
        when(userRepository.findById(7L)).thenReturn(Optional.of(current));
        when(userRepository.findByUsername(current.getUsername())).thenReturn(Optional.of(current));
        when(userRepository.findByEmail(current.getEmail())).thenReturn(Optional.of(current));
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(other));

        Throwable thrown = catchThrowable(() -> service.updateBasicInfo(7L, request));

        assertPhoneConflict(thrown);
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void registration_explicitly_starts_with_both_contacts_unverified() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("new-contact-user");
        request.setEmail("new-contact@example.test");
        request.setPhone("13800138009");
        request.setPassword("Password1");
        when(passwordEncoder.encode("Password1")).thenReturn("encoded");
        when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.register(request);

        var captured = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captured.capture());
        assertThat(captured.getValue().isEmailVerified()).isFalse();
        assertThat(captured.getValue().isPhoneVerified()).isFalse();
    }

    @Test
    void basic_info_update_preserves_unchanged_verification_and_resets_changed_contact_only() {
        User current = user(7L, "current-user", "current@example.test", PHONE);
        current.setEmailVerified(true);
        current.setPhoneVerified(true);
        BasicInfoUpdateRequest request = new BasicInfoUpdateRequest();
        request.setPhone("13800138002");
        when(userRepository.findById(7L)).thenReturn(Optional.of(current));
        when(userRepository.findByUsername(current.getUsername())).thenReturn(Optional.of(current));
        when(userRepository.findByEmail(current.getEmail())).thenReturn(Optional.of(current));
        when(userRepository.findByPhone("13800138002")).thenReturn(Optional.empty());
        when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.updateBasicInfo(7L, request);

        assertThat(current.isEmailVerified()).isTrue();
        assertThat(current.isPhoneVerified()).isFalse();
    }

    @Test
    void basic_info_update_with_same_contacts_preserves_both_verification_flags() {
        User current = user(7L, "current-user", "current@example.test", PHONE);
        current.setEmailVerified(true);
        current.setPhoneVerified(true);
        BasicInfoUpdateRequest request = new BasicInfoUpdateRequest();
        request.setEmail(current.getEmail());
        request.setPhone(current.getPhone());
        when(userRepository.findById(7L)).thenReturn(Optional.of(current));
        when(userRepository.findByUsername(current.getUsername())).thenReturn(Optional.of(current));
        when(userRepository.findByEmail(current.getEmail())).thenReturn(Optional.of(current));
        when(userRepository.findByPhone(current.getPhone())).thenReturn(Optional.of(current));
        when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.updateBasicInfo(7L, request);

        assertThat(current.isEmailVerified()).isTrue();
        assertThat(current.isPhoneVerified()).isTrue();
    }

    private static User user(Long id, String username, String email, String phone) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        return user;
    }

    private static void assertPhoneConflict(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException exception = (ResponseStatusException) thrown;
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).isEqualTo("该手机号已被占用");
    }
}
