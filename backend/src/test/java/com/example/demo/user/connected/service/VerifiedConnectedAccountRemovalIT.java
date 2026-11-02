package com.example.demo.user.connected.service;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import com.example.demo.user.connected.dto.ConnectedAccountDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.auth.verify.mode=off",
        "app.rabbit.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class VerifiedConnectedAccountRemovalIT {
    private static final String DB_NAME = "verified_unlink_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = "KnownPassword1";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private ConnectedAccountsService accounts;
    @Autowired private UserConnectedAccountRepository bindings;
    @MockitoBean private DistributedRateLimiter rateLimiter;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DB_NAME
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false;LOCK_TIMEOUT=10000");
    }

    @Test
    void existing_short_password_can_login_and_verify_unlink() throws Exception {
        User user = createUser("proof_legacy", "proof_legacy@example.test", null, "old123");
        accounts.bindExternalAccount(user.getId(), "github", "proof-legacy-gh", "GitHub");
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"proof_legacy@example.test\",\"password\":\"old123\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "proof_legacy@example.test", "   ")))
                .andExpect(status().isBadRequest());
        assertThat(accounts.findUserIdByExternalAccount("github", "proof-legacy-gh")).isEqualTo(user.getId());
        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "proof_legacy@example.test", "old123")))
                .andExpect(status().isNoContent());
        assertThat(accounts.findUserIdByExternalAccount("github", "proof-legacy-gh")).isNull();
    }

    @Test
    void valid_email_password_removes_only_the_selected_binding_and_can_log_in() throws Exception {
        User user = createUser("proof_email", "proof_email@example.test", null, PASSWORD);
        accounts.bindExternalAccount(user.getId(), "github", "proof-email-gh", "GitHub");
        accounts.bindExternalAccount(user.getId(), "google", "proof-email-google", "Google");
        String tag = bindingTag(user);

        mockMvc.perform(post("/api/v1/users/me/connected-accounts/GITHUB/disconnect")
                        .header("Authorization", bearer(user)).header("X-Device-Id", "fixture-browser").contentType(MediaType.APPLICATION_JSON)
                        .content(proof("  PROOF_EMAIL@example.test  ", PASSWORD, tag)))
                .andExpect(status().isNoContent());

        assertThat(accounts.findUserIdByExternalAccount("github", "proof-email-gh")).isNull();
        assertThat(accounts.findUserIdByExternalAccount("google", "proof-email-google")).isEqualTo(user.getId());
        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).header("X-Device-Id", "fixture-browser").contentType(MediaType.APPLICATION_JSON)
                        .content(proof("proof_email@example.test", PASSWORD, tag)))
                .andExpect(status().isConflict());
        verify(rateLimiter, times(2)).enforce("password-change-user", user.getId().toString(), 3600, 10);
        verify(rateLimiter, times(2)).enforce("login-account", "proof_email@example.test", 600, 12);
        verify(rateLimiter, times(2)).enforce("login-ip", "127.0.0.1", 60, 30);
        verify(rateLimiter, times(2)).enforce("login-device", "fixture-browser", 600, 20);
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"proof_email@example.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(user.getId()));
    }

    @Test
    void valid_phone_password_allows_unlink_and_subsequent_password_login() throws Exception {
        User user = createUser("proof_phone", null, "13900001701", PASSWORD);
        accounts.bindExternalAccount(user.getId(), "github", "proof-phone-gh", "GitHub");

        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "13900001701", PASSWORD)))
                .andExpect(status().isNoContent());

        assertThat(accounts.findUserIdByExternalAccount("github", "proof-phone-gh")).isNull();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13900001701\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(user.getId()));
    }

    @Test
    void wrong_or_another_users_credentials_never_remove_the_binding() throws Exception {
        User user = createUser("proof_original", null, null, PASSWORD);
        createUser("proof_other", "proof_other@example.test", null, PASSWORD);
        accounts.bindExternalAccount(user.getId(), "github", "proof-original-gh", "GitHub");

        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "proof_other@example.test", PASSWORD)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "not-a-login", "WrongPassword2")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "proof_other@example.test", PASSWORD)))
                .andExpect(status().isUnauthorized());

        assertThat(accounts.findUserIdByExternalAccount("github", "proof-original-gh")).isEqualTo(user.getId());
    }

    @Test
    void wrong_password_and_missing_fields_are_rejected_without_unlinking() throws Exception {
        User user = createUser("proof_wrong", "proof_wrong@example.test", null, PASSWORD);
        accounts.bindExternalAccount(user.getId(), "github", "proof-wrong-gh", "GitHub");

        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "proof_wrong@example.test", "WrongPassword2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("邮箱、手机号或密码不正确"));
        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"proof_wrong@example.test\"}"))
                .andExpect(status().isBadRequest());

        assertThat(accounts.findUserIdByExternalAccount("github", "proof-wrong-gh")).isEqualTo(user.getId());
    }

    @Test
    void unavailable_password_limit_fails_before_unlink() throws Exception {
        User user = createUser("proof_limited", "proof_limited@example.test", null, PASSWORD);
        accounts.bindExternalAccount(user.getId(), "github", "proof-limited-gh", "GitHub");
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用"))
                .when(rateLimiter).enforce("password-change-user", user.getId().toString(), 3600, 10);

        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "proof_limited@example.test", PASSWORD)))
                .andExpect(status().isServiceUnavailable());

        assertThat(accounts.findUserIdByExternalAccount("github", "proof-limited-gh")).isEqualTo(user.getId());
    }

    @Test
    void exhausted_login_address_limit_fails_before_unlink() throws Exception {
        User user = createUser("proof_exhausted", "proof_exhausted@example.test", null, PASSWORD);
        accounts.bindExternalAccount(user.getId(), "github", "proof-exhausted-gh", "GitHub");
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"))
                .when(rateLimiter).enforce("login-account", "proof_exhausted@example.test", 600, 12);

        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "proof_exhausted@example.test", PASSWORD)))
                .andExpect(status().isTooManyRequests());

        assertThat(accounts.findUserIdByExternalAccount("github", "proof-exhausted-gh")).isEqualTo(user.getId());
    }

    @Test
    void exhausted_login_ip_limit_fails_before_unlink() throws Exception {
        User user = createUser("proof_ip", "proof_ip@example.test", null, PASSWORD);
        accounts.bindExternalAccount(user.getId(), "github", "proof-ip-gh", "GitHub");
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"))
                .when(rateLimiter).enforce("login-ip", "127.0.0.1", 60, 30);

        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof(user, "proof_ip@example.test", PASSWORD)))
                .andExpect(status().isTooManyRequests());

        assertThat(accounts.findUserIdByExternalAccount("github", "proof-ip-gh")).isEqualTo(user.getId());
    }

    @Test
    void stale_binding_snapshot_cannot_remove_a_replacement() throws Exception {
        User user = createUser("proof_stale", "proof_stale@example.test", null, PASSWORD);
        accounts.bindExternalAccount(user.getId(), "github", "proof-stale-old", "GitHub");
        String originalTag = bindingTag(user);
        var binding = bindings.findByUserIdAndProvider(user.getId(), "github").orElseThrow();
        binding.setExternalId("proof-stale-new");
        bindings.saveAndFlush(binding);

        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof("proof_stale@example.test", PASSWORD, originalTag)))
                .andExpect(status().isConflict());

        assertThat(accounts.findUserIdByExternalAccount("github", "proof-stale-new")).isEqualTo(user.getId());
    }

    @Test
    void an_old_snapshot_cannot_remove_a_binding_created_after_unlink() throws Exception {
        User user = createUser("proof_rebind", "proof_rebind@example.test", null, PASSWORD);
        accounts.bindExternalAccount(user.getId(), "github", "proof-rebind-old", "Old");
        String oldTag = bindingTag(user);
        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof("proof_rebind@example.test", PASSWORD, oldTag)))
                .andExpect(status().isNoContent());
        accounts.bindExternalAccount(user.getId(), "github", "proof-rebind-new", "New");

        assertThat(bindingTag(user)).isNotEqualTo(oldTag);
        mockMvc.perform(post("/api/v1/users/me/connected-accounts/github/disconnect")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(proof("proof_rebind@example.test", PASSWORD, oldTag)))
                .andExpect(status().isConflict());
        assertThat(accounts.findUserIdByExternalAccount("github", "proof-rebind-new")).isEqualTo(user.getId());
    }

    private User createUser(String username, String email, String phone, String password) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(passwords.encode(password));
        return users.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + tokens.generateToken(user.getId(), user.getUsername());
    }

    @SuppressWarnings("unchecked")
    private String bindingTag(User user) {
        List<ConnectedAccountDto> items = (List<ConnectedAccountDto>) accounts.list(user.getId()).get("items");
        return items.stream().filter(item -> "github".equals(item.getProvider())).findFirst().orElseThrow().getBindingTag();
    }

    private String proof(User user, String identifier, String password) {
        return proof(identifier, password, bindingTag(user));
    }

    private String proof(String identifier, String password, String tag) {
        return "{\"identifier\":\"" + identifier + "\",\"currentPassword\":\"" + password
                + "\",\"bindingTag\":\"" + tag + "\"}";
    }
}
