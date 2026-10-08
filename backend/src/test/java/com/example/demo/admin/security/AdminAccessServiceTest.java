package com.example.demo.admin.security;

import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccessServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void blank_configuration_disables_admin_without_a_user_lookup() {
        AdminAccessService service = new AdminAccessService(userRepository, "  ");

        service.resolveConfiguredAdmin();

        assertFalse(service.isAdmin(42L));
        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.requireAdmin(42L));
        assertEquals(HttpStatus.FORBIDDEN.value(), failure.getStatusCode().value());
        verifyNoInteractions(userRepository);
    }

    @Test
    void configured_email_must_match_an_existing_account_during_startup_resolution() {
        when(userRepository.findAllByEmailIgnoreCase("missing@example.com")).thenReturn(List.of());
        AdminAccessService service = new AdminAccessService(userRepository, "missing@example.com");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("app.admin.email"));
        assertTrue(failure.getMessage().contains("existing user account"));
        assertFalse(failure.getMessage().contains("missing@example.com"));
    }

    @Test
    void owner_is_resolved_case_insensitively_and_with_trimmed_configuration() {
        when(userRepository.findAllByEmailIgnoreCase("owner@example.com")).thenReturn(List.of(user(42L, "Owner@Example.com")));
        AdminAccessService service = new AdminAccessService(userRepository, "  owner@example.com  ");

        service.resolveConfiguredAdmin();

        assertTrue(service.isAdmin(42L));
        assertDoesNotThrow(() -> service.requireAdmin(42L));
    }

    @Test
    void non_owner_is_forbidden_and_null_principal_is_unauthorized() {
        when(userRepository.findAllByEmailIgnoreCase("owner@example.com")).thenReturn(List.of(user(42L, "owner@example.com")));
        AdminAccessService service = new AdminAccessService(userRepository, "owner@example.com");
        service.resolveConfiguredAdmin();

        ResponseStatusException nonOwner = assertThrows(
                ResponseStatusException.class,
                () -> service.requireAdmin(99L));
        assertEquals(HttpStatus.FORBIDDEN.value(), nonOwner.getStatusCode().value());

        ResponseStatusException unauthenticated = assertThrows(
                ResponseStatusException.class,
                () -> service.requireAdmin(null));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), unauthenticated.getStatusCode().value());
    }

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }
}
