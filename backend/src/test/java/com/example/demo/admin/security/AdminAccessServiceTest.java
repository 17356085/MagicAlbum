package com.example.demo.admin.security;

import com.example.demo.governance.service.GovernanceService;
import com.example.demo.user.service.CommunityAccounts;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccessServiceTest {

    @Mock
    private CommunityAccounts communityAccounts;

    @Mock
    private GovernanceService governanceService;

    @Test
    void blank_configuration_disables_admin_without_a_user_lookup() {
        AdminAccessService service = new AdminAccessService(communityAccounts, "  ");

        service.resolveConfiguredAdmin();

        assertFalse(service.isAdmin(42L));
        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.requireAdmin(42L));
        assertEquals(HttpStatus.FORBIDDEN.value(), failure.getStatusCode().value());
        verifyNoInteractions(communityAccounts);
    }

    @Test
    void configured_email_must_match_an_existing_account_during_startup_resolution() {
        when(communityAccounts.findBootstrapByEmailIgnoreCase("missing@example.com")).thenReturn(List.of());
        AdminAccessService service = new AdminAccessService(communityAccounts, "missing@example.com");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("app.admin.email"));
        assertTrue(failure.getMessage().contains("existing user account"));
        assertFalse(failure.getMessage().contains("missing@example.com"));
    }

    @Test
    void owner_is_resolved_case_insensitively_and_with_trimmed_configuration() {
        when(communityAccounts.findBootstrapByEmailIgnoreCase("owner@example.com"))
                .thenReturn(List.of(account(42L, "Owner@Example.com")));
        AdminAccessService service = new AdminAccessService(communityAccounts, "  owner@example.com  ");

        service.resolveConfiguredAdmin();

        assertTrue(service.isAdmin(42L));
        assertDoesNotThrow(() -> service.requireAdmin(42L));
    }

    @Test
    void email_only_bootstrap_rejects_unverified_account_without_granting_admin() {
        when(communityAccounts.findBootstrapByEmailIgnoreCase("owner@example.com"))
                .thenReturn(List.of(account(42L, "owner@example.com", false)));
        AdminAccessService service = new AdminAccessService(
                communityAccounts, governanceService, "owner@example.com");

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
        when(communityAccounts.findBootstrapByEmailIgnoreCase("owner@example.com"))
                .thenReturn(List.of(account(42L, "owner@example.com", true)));
        when(governanceService.isAdmin(42L)).thenReturn(true);
        AdminAccessService service = new AdminAccessService(
                communityAccounts, governanceService, "owner@example.com");

        service.resolveConfiguredAdmin();

        verify(governanceService).bootstrapAdmin(42L);
        assertTrue(service.isAdmin(42L));
    }

    @Test
    void explicit_id_selects_exact_user_without_requiring_email_verification() {
        when(communityAccounts.findBootstrapById(42L))
                .thenReturn(Optional.of(account(42L, "owner@example.com", false)));
        when(governanceService.isAdmin(42L)).thenReturn(true);
        AdminAccessService service = new AdminAccessService(
                communityAccounts, governanceService, "", 42L);

        service.resolveConfiguredAdmin();

        verify(communityAccounts).findBootstrapById(42L);
        verify(communityAccounts, never()).findBootstrapByEmailIgnoreCase("owner@example.com");
        verify(governanceService).bootstrapAdmin(42L);
        assertTrue(service.isAdmin(42L));
    }

    @Test
    void explicit_id_must_match_an_existing_user() {
        when(communityAccounts.findBootstrapById(42L)).thenReturn(Optional.empty());
        AdminAccessService service = new AdminAccessService(
                communityAccounts, governanceService, "", 42L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("app.admin.user-id"));
        verifyNoInteractions(governanceService);
    }

    @Test
    void explicit_id_and_email_must_identify_the_same_user() {
        when(communityAccounts.findBootstrapById(42L))
                .thenReturn(Optional.of(account(42L, "other@example.com", false)));
        AdminAccessService service = new AdminAccessService(
                communityAccounts, governanceService, "owner@example.com", 42L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("selected user account"));
        assertFalse(failure.getMessage().contains("owner@example.com"));
        verify(communityAccounts, never()).findBootstrapByEmailIgnoreCase("owner@example.com");
        verifyNoInteractions(governanceService);
    }

    @Test
    void explicit_id_and_email_reject_a_user_with_no_email() {
        when(communityAccounts.findBootstrapById(42L))
                .thenReturn(Optional.of(account(42L, null, false)));
        AdminAccessService service = new AdminAccessService(
                communityAccounts, governanceService, "owner@example.com", 42L);

        IllegalStateException failure = assertThrows(IllegalStateException.class, service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("selected user account"));
        verifyNoInteractions(governanceService);
    }

    @Test
    void explicit_id_without_configured_email_can_select_a_user_without_email() {
        when(communityAccounts.findBootstrapById(42L))
                .thenReturn(Optional.of(account(42L, null, false)));
        when(governanceService.isAdmin(42L)).thenReturn(true);
        AdminAccessService service = new AdminAccessService(communityAccounts, governanceService, "", 42L);

        service.resolveConfiguredAdmin();

        verify(governanceService).bootstrapAdmin(42L);
        assertTrue(service.isAdmin(42L));
    }

    @Test
    void negative_explicit_id_fails_before_any_lookup_or_bootstrap() {
        AdminAccessService service = new AdminAccessService(
                communityAccounts, governanceService, "", -1L);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::resolveConfiguredAdmin);

        assertTrue(failure.getMessage().contains("app.admin.user-id"));
        verifyNoInteractions(communityAccounts, governanceService);
    }

    @Test
    void non_owner_is_forbidden_and_null_principal_is_unauthorized() {
        when(communityAccounts.findBootstrapByEmailIgnoreCase("owner@example.com"))
                .thenReturn(List.of(account(42L, "owner@example.com")));
        AdminAccessService service = new AdminAccessService(communityAccounts, "owner@example.com");
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
        when(communityAccounts.findBootstrapByEmailIgnoreCase("owner@example.com"))
                .thenReturn(List.of(account(42L, "owner@example.com")));
        when(governanceService.isAdmin(42L)).thenReturn(true);

        AdminAccessService service = new AdminAccessService(
                communityAccounts, governanceService, "owner@example.com");

        service.resolveConfiguredAdmin();

        verify(governanceService).bootstrapAdmin(42L);
        assertTrue(service.isAdmin(42L));
    }

    @Test
    void missing_bootstrap_email_does_not_consume_governance_state() {
        AdminAccessService service = new AdminAccessService(
                communityAccounts, governanceService, "  ");

        service.resolveConfiguredAdmin();

        verifyNoInteractions(communityAccounts, governanceService);
    }

    private CommunityAccounts.BootstrapAccount account(Long id, String email) {
        return account(id, email, true);
    }

    private CommunityAccounts.BootstrapAccount account(Long id, String email, boolean emailVerified) {
        return new CommunityAccounts.BootstrapAccount(id, email, emailVerified);
    }
}
