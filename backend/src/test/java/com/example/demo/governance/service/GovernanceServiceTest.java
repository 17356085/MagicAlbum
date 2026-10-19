package com.example.demo.governance.service;

import com.example.demo.governance.entity.CommunityState;
import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.governance.repo.CommunityAdminRoleRepository;
import com.example.demo.governance.repo.CommunityAuditRepository;
import com.example.demo.governance.repo.CommunityModeratorScopeRepository;
import com.example.demo.governance.repo.CommunityPostingRestrictionRepository;
import com.example.demo.governance.repo.CommunityStateRepository;
import com.example.demo.governance.repo.SectionGovernanceRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernanceServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private SectionRepository sectionRepository;
    @Mock
    private SectionGovernanceRepository sectionGovernanceRepository;
    @Mock
    private CommunityStateRepository stateRepository;
    @Mock
    private CommunityAdminRoleRepository adminRoleRepository;
    @Mock
    private CommunityModeratorScopeRepository moderatorScopeRepository;
    @Mock
    private CommunityPostingRestrictionRepository restrictionRepository;
    @Mock
    private CommunityAuditRepository auditRepository;

    private GovernanceService service;

    @BeforeEach
    void setUp() {
        service = new GovernanceService(userRepository, sectionRepository, sectionGovernanceRepository,
                stateRepository, adminRoleRepository, moderatorScopeRepository,
                restrictionRepository, auditRepository);
    }

    @Test
    void normalize_reason_trims_and_rejects_empty_or_oversized_values() {
        assertEquals("because", service.normalizeReason("  because  "));
        assertThrows(ResponseStatusException.class, () -> service.normalizeReason("  "));
        assertThrows(ResponseStatusException.class, () -> service.normalizeReason("x".repeat(501)));
    }

    @Test
    void access_reports_active_restriction_and_scope_without_sensitive_user_fields() {
        Instant until = Instant.now().plusSeconds(60);
        when(adminRoleRepository.existsById(7L)).thenReturn(false);
        when(moderatorScopeRepository.findSectionIdsByUserId(7L)).thenReturn(List.of(9L, 9L));
        var restriction = new com.example.demo.governance.entity.CommunityPostingRestriction(7L, until);
        when(restrictionRepository.findById(7L)).thenReturn(Optional.of(restriction));

        GovernanceService.Access access = service.access(7L);

        assertFalse(access.admin());
        assertEquals(List.of(9L), access.moderatedSectionIds());
        assertTrue(access.postingRestricted());
        assertEquals(until, access.restrictedUntil());
    }

    @Test
    void bootstrap_does_not_resurrect_a_role_after_state_is_marked() {
        CommunityState state = new CommunityState();
        state.setBootstrapped(true);
        User user = user(42L);
        when(stateRepository.findByIdForUpdate(CommunityState.SINGLETON_ID)).thenReturn(Optional.of(state));
        when(userRepository.findByIdForInteraction(42L)).thenReturn(Optional.of(user));

        service.bootstrapAdmin(42L);

        verify(adminRoleRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertTrue(state.isBootstrapped());
    }

    @Test
    void last_admin_cannot_be_revoked() {
        CommunityState state = new CommunityState();
        when(adminRoleRepository.existsById(1L)).thenReturn(true);
        when(stateRepository.findByIdForUpdate(CommunityState.SINGLETON_ID)).thenReturn(Optional.of(state));
        when(userRepository.findByIdForInteraction(1L)).thenReturn(Optional.of(user(1L)));
        when(adminRoleRepository.count()).thenReturn(1L);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.setAdmin(1L, 1L, false, "  keep one  "));

        assertEquals(HttpStatus.CONFLICT.value(), failure.getStatusCode().value());
        verify(adminRoleRepository, never()).deleteById(1L);
        verify(auditRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void write_gate_locks_user_before_section_and_rejects_restriction() {
        User user = user(7L);
        Section section = new Section();
        section.setId(9L);
        when(userRepository.findByIdForInteraction(7L)).thenReturn(Optional.of(user));
        when(sectionGovernanceRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(section));
        when(restrictionRepository.findById(7L)).thenReturn(Optional.of(
                new com.example.demo.governance.entity.CommunityPostingRestriction(7L, null)));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.assertCanWrite(7L, 9L));

        assertEquals(HttpStatus.FORBIDDEN.value(), failure.getStatusCode().value());
        InOrder order = inOrder(userRepository, sectionGovernanceRepository);
        order.verify(userRepository).findByIdForInteraction(7L);
        order.verify(sectionGovernanceRepository).findByIdForUpdate(9L);
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        return user;
    }
}
