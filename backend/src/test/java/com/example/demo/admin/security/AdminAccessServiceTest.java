package com.example.demo.admin.security;

import com.example.demo.governance.service.GovernanceService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccessServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private GovernanceService governanceService;

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
    void email_only_bootstrap_rejects_unverified_account_without_granting_admin() {
        when(userRepository.findAllByEmailIgnoreCase("owner@example.com"))
                .thenReturn(List.of(user(42L, "owner@example.com", false)));
        AdminAccessService service = new AdminAccessService(
                userRepository, governanceService, "owner@example.com");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("unverified"));
        assertFalse(failure.getMessage().contains("owner@example.com"));
        verifyNoInteractions(governanceService);
        verify(governanceService, never()).bootstrapAdmin(42L);
    }

    @Test
    void email_only_bootstrap_accepts_verified_account_and_persists_the_one_time_grant() {
        when(userRepository.findAllByEmailIgnoreCase("owner@example.com"))
                .thenReturn(List.of(user(42L, "owner@example.com", true)));
        when(governanceService.isAdmin(42L)).thenReturn(true);
        AdminAccessService service = new AdminAccessService(
                userRepository, governanceService, "owner@example.com");

        service.resolveConfiguredAdmin();

        verify(governanceService).bootstrapAdmin(42L);
        assertTrue(service.isAdmin(42L));
    }

    @Test
    void explicit_id_selects_exact_user_without_requiring_email_verification() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(42L, "owner@example.com", false)));
        when(governanceService.isAdmin(42L)).thenReturn(true);
        AdminAccessService service = new AdminAccessService(
                userRepository, governanceService, "", 42L);

        service.resolveConfiguredAdmin();

        verify(userRepository).findById(42L);
        verify(userRepository, never()).findAllByEmailIgnoreCase("owner@example.com");
        verify(governanceService).bootstrapAdmin(42L);
        assertTrue(service.isAdmin(42L));
    }

    @Test
    void explicit_id_must_match_an_existing_user() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        AdminAccessService service = new AdminAccessService(
                userRepository, governanceService, "", 42L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("app.admin.user-id"));
        verifyNoInteractions(governanceService);
    }

    @Test
    void explicit_id_and_email_must_identify_the_same_user() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(42L, "other@example.com", false)));
        AdminAccessService service = new AdminAccessService(
                userRepository, governanceService, "owner@example.com", 42L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("selected user account"));
        assertFalse(failure.getMessage().contains("owner@example.com"));
        verify(userRepository, never()).findAllByEmailIgnoreCase("owner@example.com");
        verifyNoInteractions(governanceService);
    }

    @Test
    void negative_explicit_id_fails_before_any_lookup_or_bootstrap() {
        AdminAccessService service = new AdminAccessService(
                userRepository, governanceService, "", -1L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("app.admin.user-id"));
        verifyNoInteractions(userRepository, governanceService);
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

    @Test
    void production_constructor_bootstraps_only_the_configured_existing_user() {
        when(userRepository.findAllByEmailIgnoreCase("owner@example.com"))
                .thenReturn(List.of(user(42L, "owner@example.com")));
        when(governanceService.isAdmin(42L)).thenReturn(true);

        AdminAccessService service = new AdminAccessService(
                userRepository, governanceService, "owner@example.com");

        service.resolveConfiguredAdmin();

        verify(governanceService).bootstrapAdmin(42L);
        assertTrue(service.isAdmin(42L));
    }

    @Test
    void missing_bootstrap_email_does_not_consume_governance_state() {
        AdminAccessService service = new AdminAccessService(
                userRepository, governanceService, "  ");

        service.resolveConfiguredAdmin();

        verifyNoInteractions(userRepository, governanceService);
    }

    private User user(Long id, String email) {
        return user(id, email, true);
    }

    private User user(Long id, String email, boolean emailVerified) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setEmailVerified(emailVerified);
        return user;
    }
}
