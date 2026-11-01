package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.github.GithubUserProfile;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real authenticated API and persistence on an isolated in-memory database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.rabbit.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class ConnectedAccountDisconnectSafetyIT {
    private static final String DATABASE_NAME = "disconnect_safety_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private ConnectedAccountsService accounts;
    @Autowired private OAuthUserProvisioningService provisioning;
    @Autowired private JwtTokenProvider tokens;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE_NAME
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false");
    }

    @Test
    void unauthenticated_unlink_cannot_modify_a_binding() throws Exception {
        User user = createGithubAccount(817011L);

        mockMvc.perform(delete("/api/v1/users/me/connected-accounts/github"))
                .andExpect(status().isUnauthorized());

        assertThat(accounts.findUserIdByExternalAccount("github", "817011")).isEqualTo(user.getId());
    }

    @Test
    void sole_oauth_identity_cannot_be_unlinked_through_the_api() throws Exception {
        User user = createGithubAccount(817012L);
        String token = tokens.generateToken(user.getId(), user.getUsername());

        mockMvc.perform(delete("/api/v1/users/me/connected-accounts/github")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("暂不支持解绑第三方账号"));

        assertThat(accounts.findUserIdByExternalAccount("github", "817012")).isEqualTo(user.getId());
    }

    @Test
    void a_later_provider_login_still_resolves_to_the_original_user() throws Exception {
        User user = createGithubAccount(817013L);
        String token = tokens.generateToken(user.getId(), user.getUsername());

        mockMvc.perform(delete("/api/v1/users/me/connected-accounts/github")
                .header("Authorization", "Bearer " + token));
        GithubUserProfile profile = new GithubUserProfile();
        profile.setId(817013L);
        profile.setLogin("disconnect-safety-817013");

        assertThat(provisioning.resolveOrCreateGithubUser(profile).getId()).isEqualTo(user.getId());
    }

    @Test
    void unlink_is_unavailable_even_when_another_binding_exists_or_provider_is_missing() throws Exception {
        User user = createGithubAccount(817014L);
        accounts.bindExternalAccount(user.getId(), "google", "google-817014", "test google");
        String token = tokens.generateToken(user.getId(), user.getUsername());

        mockMvc.perform(delete("/api/v1/users/me/connected-accounts/github")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/users/me/connected-accounts/apple")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        assertThat(accounts.findUserIdByExternalAccount("github", "817014")).isEqualTo(user.getId());
        assertThat(accounts.findUserIdByExternalAccount("google", "google-817014")).isEqualTo(user.getId());
    }

    private User createGithubAccount(long externalId) {
        User user = new User();
        user.setUsername("disconnect_safety_" + externalId);
        user.setPasswordHash("test-only-unknown-password-hash");
        User saved = users.saveAndFlush(user);
        accounts.bindExternalAccount(saved.getId(), "github", Long.toString(externalId), "test github");
        return saved;
    }
}
