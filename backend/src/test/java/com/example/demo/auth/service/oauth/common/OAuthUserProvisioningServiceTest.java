package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.dto.apple.AppleIdTokenClaims;
import com.example.demo.auth.dto.github.GithubUserProfile;
import com.example.demo.auth.dto.google.GoogleUserProfile;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthUserProvisioningServiceTest {
    private UserRepository users;
    private UserProfileRepository profiles;
    private ConnectedAccountsService accounts;
    private PasswordEncoder passwords;
    private OAuthUserProvisioningService provisioning;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(UserProfileRepository.class);
        accounts = mock(ConnectedAccountsService.class);
        when(accounts.findUserIdByExternalAccount(anyString(), anyString())).thenReturn(null);
        passwords = mock(PasswordEncoder.class);
        when(passwords.encode(anyString())).thenReturn("encoded-oauth-password");
        when(users.existsByUsername(anyString())).thenReturn(false);
        when(users.existsByEmail(anyString())).thenReturn(false);
        when(users.existsByPhone(anyString())).thenReturn(false);
        when(profiles.findById(anyLong())).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(9001L);
            return saved;
        });
        provisioning = OAuthUserProvisioningService.forTest(users, profiles, accounts, passwords);
    }

    @Test
    void unverifiedProviderEmailCannotReuseAnExistingLocalAccount() {
        User local = user(11L, "victim@example.com");
        when(users.findByEmail("victim@example.com")).thenReturn(Optional.of(local));

        GoogleUserProfile profile = new GoogleUserProfile();
        profile.setSub("google-unverified");
        profile.setEmail("victim@example.com");
        profile.setEmailVerified(false);

        User provisioned = provisioning.resolveOrCreateGoogleUser(profile);

        assertThat(provisioned.getId()).isEqualTo(9001L);
        assertThat(provisioned.getEmail()).endsWith("@oauth.invalid");
        assertThat(provisioned.getEmail()).isNotEqualTo("victim@example.com");
        assertThat(provisioned.isEmailVerified()).isFalse();
        assertThat(provisioned.isPhoneVerified()).isFalse();
        verify(users, never()).findByEmail("victim@example.com");
        verify(accounts).bindExternalAccount(org.mockito.ArgumentMatchers.eq(9001L),
                org.mockito.ArgumentMatchers.eq("google"), org.mockito.ArgumentMatchers.eq("google-unverified"), any());
    }

    @Test
    void verifiedProviderEmailConflictReturns409WithoutBinding() {
        User local = user(12L, "victim@example.com");
        when(users.findAllByEmailIgnoreCase("victim@example.com")).thenReturn(List.of(local));

        GoogleUserProfile profile = new GoogleUserProfile();
        profile.setSub("google-conflict");
        profile.setEmail("Victim@Example.com");
        profile.setEmailVerified(true);

        assertThatThrownBy(() -> provisioning.resolveOrCreateGoogleUser(profile))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("请使用原登录方式");
                });

        verify(users, never()).save(any(User.class));
        verify(accounts, never()).bindExternalAccount(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void githubDoesNotReuseProviderEmailOrPredictableFallback() {
        User fallback = user(13L, "github_12345@oauth.local");
        when(users.findByEmail("github_12345@oauth.local")).thenReturn(Optional.of(fallback));

        GithubUserProfile profile = new GithubUserProfile();
        profile.setId(12345L);
        profile.setLogin("github-user");
        profile.setEmail("victim@example.com");

        User provisioned = provisioning.resolveOrCreateGithubUser(profile);

        assertThat(provisioned.getId()).isEqualTo(9001L);
        assertThat(provisioned.getEmail()).endsWith("@oauth.invalid");
        assertThat(provisioned.getEmail()).isNotEqualTo("victim@example.com");
        assertThat(provisioned.getEmail()).isNotEqualTo("github_12345@oauth.local");
        assertThat(provisioned.isEmailVerified()).isFalse();
        assertThat(provisioned.isPhoneVerified()).isFalse();
        verify(users, never()).findByEmail(anyString());
        verify(accounts).bindExternalAccount(org.mockito.ArgumentMatchers.eq(9001L),
                org.mockito.ArgumentMatchers.eq("github"), org.mockito.ArgumentMatchers.eq("12345"), any());
    }

    @Test
    void verifiedGoogleEmailBecomesTheOnlyNewRealEmail() {
        GoogleUserProfile profile = new GoogleUserProfile();
        profile.setSub("google-verified");
        profile.setName("Verified Google");
        profile.setEmail("Verified@Example.com");
        profile.setEmailVerified(true);

        User provisioned = provisioning.resolveOrCreateGoogleUser(profile);

        assertThat(provisioned.getEmail()).isEqualTo("verified@example.com");
        assertThat(provisioned.isEmailVerified()).isTrue();
        assertThat(provisioned.isPhoneVerified()).isFalse();
    }

    @Test
    void missingAppleEmailUsesSyntheticUnverifiedContact() {
        AppleIdTokenClaims claims = new AppleIdTokenClaims();
        claims.setSub("apple-no-email");
        claims.setEmailVerified(true);

        User provisioned = provisioning.resolveOrCreateAppleUser(claims);

        assertThat(provisioned.getEmail()).endsWith("@oauth.invalid");
        assertThat(provisioned.isEmailVerified()).isFalse();
        assertThat(provisioned.isPhoneVerified()).isFalse();
    }

    @Test
    void stableExternalIdMappingWinsEvenWhenProviderEmailChanges() {
        User existing = user(77L, "old@example.com");
        when(accounts.findUserIdByExternalAccount("apple", "apple-stable")).thenReturn(77L);
        when(users.findById(77L)).thenReturn(Optional.of(existing));

        AppleIdTokenClaims claims = new AppleIdTokenClaims();
        claims.setSub("apple-stable");
        claims.setEmail("new@example.com");
        claims.setEmailVerified(false);

        User resolved = provisioning.resolveOrCreateAppleUser(claims);

        assertThat(resolved).isSameAs(existing);
        verify(users, never()).save(any(User.class));
        verify(accounts, never()).bindExternalAccount(anyLong(), anyString(), anyString(), anyString());
        verify(users, never()).findAllByEmailIgnoreCase(anyString());
    }

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername("local-" + id);
        user.setEmail(email);
        user.setPhone("18800000000");
        user.setPasswordHash("encoded");
        return user;
    }
}
