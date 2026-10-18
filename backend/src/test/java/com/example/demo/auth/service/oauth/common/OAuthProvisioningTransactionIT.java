package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.dto.github.GithubUserProfile;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.repo.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:oauth_atomic;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false", "spring.jpa.show-sql=false"})
class OAuthProvisioningTransactionIT {
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager manager;
    @Autowired ConnectedAccountsService accounts;
    @Autowired OAuthUserProvisioningService provisioning;

    private GithubUserProfile profile(long id) {
        var profile = new GithubUserProfile();
        profile.setId(id); profile.setLogin("oauth_atomic_" + id);
        profile.setEmail("atomic_" + id + "@example.test");
        return profile;
    }

    @Test void failureAfterUserInsertRollsBackTheEntireProvisioning() {
        var binding = mock(ConnectedAccountsService.class);
        when(binding.findUserIdByExternalAccount(any(), any())).thenReturn(null);
        doThrow(new IllegalStateException("binding failed")).when(binding).bindExternalAccount(any(), any(), any(), any());
        var service = new OAuthUserProvisioningService(users, profiles, binding, passwords, manager);
        var profile = profile(81001L);
        assertThatThrownBy(() -> service.resolveOrCreateGithubUser(profile)).isInstanceOf(IllegalStateException.class);
        assertThat(users.findByEmail(profile.getEmail())).isEmpty();
    }

    @Test void concurrentFirstLoginReturnsOneCompleteIdentityWithoutAnOrphan() throws Exception {
        var profile = profile(81002L);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<User> login = () -> { start.await(); return provisioning.resolveOrCreateGithubUser(profile); };
            var first = pool.submit(login); var second = pool.submit(login); start.countDown();
            User a = first.get(30, TimeUnit.SECONDS), b = second.get(30, TimeUnit.SECONDS);
            assertThat(a.getId()).isEqualTo(b.getId());
            assertThat(accounts.findUserIdByExternalAccount("github", "81002")).isEqualTo(a.getId());
            assertThat(profiles.findById(a.getId())).isPresent();
            assertThat(users.findAll().stream().filter(u -> u.getUsername().startsWith("oauth_atomic_81002"))).hasSize(1);
        }
    }

    @Test void otpProvisioningAlsoRollsBackWhenProfileCreationFails() {
        var client = mock(com.example.demo.auth.service.otp.CognitoOtpClient.class);
        var authenticated = new com.example.demo.auth.dto.CognitoAuthFlowResponse();
        var result = new com.example.demo.auth.dto.CognitoAuthenticationResult(); result.setAccessToken("test-cognito");
        authenticated.setAuthenticationResult(result);
        when(client.respondToChallenge(any(), any(), any(), any())).thenReturn(authenticated);
        var failingProfiles = mock(UserProfileRepository.class);
        when(failingProfiles.findById(any())).thenReturn(java.util.Optional.empty());
        when(failingProfiles.save(any())).thenThrow(new IllegalStateException("profile write failed"));
        var service = new com.example.demo.auth.service.otp.CognitoOtpAuthService(client, users, failingProfiles,
                passwords, mock(com.example.demo.auth.JwtTokenProvider.class),
                mock(com.example.demo.auth.service.otp.AuthVerifyService.class), 300, 300, 60, manager);
        var request = new com.example.demo.auth.dto.AuthCodeFinishRequest();
        request.setChannel(com.example.demo.auth.dto.AuthCodeChannel.email);
        request.setAddress("otp-atomic@example.test"); request.setSession("session"); request.setCode("123456");
        assertThatThrownBy(() -> service.finish(request)).isInstanceOf(IllegalStateException.class);
        assertThat(users.findByEmail("otp-atomic@example.test")).isEmpty();
    }
}
