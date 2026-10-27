package com.example.demo.user.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisabledInAotMode
@MockitoBean(types = StringRedisTemplate.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:password_change_security;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "feature.mp.read.threads=false",
        "app.auth.verify.mode=off"
})
class PasswordChangeSecurityIT {
    private static final String CURRENT_PASSWORD = "CurrentPassword1";
    private static final String NEW_PASSWORD = "NewPassword2";
    private static final String WRONG_PASSWORD = "WrongPassword1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Long userId;
    private String validToken;
    private String originalHash;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        User user = new User();
        user.setUsername("password-change-user");
        user.setEmail("password-change-user@example.test");
        user.setPhone("13900000001");
        user.setPasswordHash(passwordEncoder.encode(CURRENT_PASSWORD));
        user = userRepository.saveAndFlush(user);

        userId = user.getId();
        originalHash = user.getPasswordHash();
        validToken = jwtTokenProvider.generateToken(userId, user.getUsername());
    }

    @Test
    void wrong_current_password_is_bad_request_and_does_not_change_persisted_hash() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson(WRONG_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("当前密码不正确"));

        User stored = userRepository.findById(userId).orElseThrow();
        assertThat(stored.getPasswordHash()).isEqualTo(originalHash);
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, stored.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash())).isFalse();
    }

    @Test
    void valid_current_password_changes_hash_and_returns_no_content() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        User stored = userRepository.findById(userId).orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo(originalHash);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, stored.getPasswordHash())).isFalse();
    }

    @Test
    void absent_or_invalid_bearer_cannot_change_password() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());
        assertHashStillMatchesCurrentPassword();

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer definitely-not-a-valid-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());
        assertHashStillMatchesCurrentPassword();
    }

    @Test
    void weak_new_password_is_bad_request_and_does_not_change_hash() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson(CURRENT_PASSWORD, "weakpassword")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("新密码需包含大小写字母和数字"));

        assertHashStillMatchesCurrentPassword();
    }

    @Test
    void exact_72_utf8_byte_passwords_register_successfully() throws Exception {
        String[] passwords = {ascii72(), han72(), emoji72()};
        String[] usernames = {"exact-ascii-user", "exact-han-user", "exact-emoji-user"};
        String[] emails = {"exact-ascii@example.test", "exact-han@example.test", "exact-emoji@example.test"};
        String[] phones = {"13900000002", "13900000003", "13900000004"};

        for (int i = 0; i < passwords.length; i++) {
            String password = passwords[i];
            assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(72);
            mockMvc.perform(post("/api/v1/users/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerJson(usernames[i], emails[i], phones[i], password)))
                    .andExpect(status().isCreated());

            User stored = userRepository.findByUsername(usernames[i]).orElseThrow();
            assertThat(passwordEncoder.matches(password, stored.getPasswordHash())).isTrue();
        }
    }

    @Test
    void overlong_registration_password_is_bad_request_and_does_not_create_user() throws Exception {
        String ascii73 = "aA1" + "x".repeat(70);
        String han75 = "aA1" + "汉".repeat(24);
        String emoji75 = "aA1" + "😀".repeat(18);
        assertThat(ascii73.getBytes(StandardCharsets.UTF_8)).hasSize(73);
        assertThat(han75.getBytes(StandardCharsets.UTF_8)).hasSize(75);
        assertThat(emoji75.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("overlong-ascii-user", "overlong-ascii@example.test", "13900000002", ascii73)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码过长，请减少字符数量"));
        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("overlong-han-user", "overlong-han@example.test", "13900000003", han75)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码过长，请减少字符数量"));
        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("overlong-emoji-user", "overlong-emoji@example.test", "13900000004", emoji75)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码过长，请减少字符数量"));

        assertThat(userRepository.existsByUsername("overlong-ascii-user")).isFalse();
        assertThat(userRepository.existsByUsername("overlong-han-user")).isFalse();
        assertThat(userRepository.existsByUsername("overlong-emoji-user")).isFalse();
    }

    @Test
    void exact_72_utf8_byte_passwords_change_successfully() throws Exception {
        String[] passwords = {ascii72(), han72(), emoji72()};
        String currentPassword = CURRENT_PASSWORD;
        String previousHash = originalHash;

        for (String nextPassword : passwords) {
            assertThat(nextPassword.getBytes(StandardCharsets.UTF_8)).hasSize(72);
            mockMvc.perform(post("/api/v1/users/me/password")
                            .header("Authorization", "Bearer " + validToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(passwordChangeJson(currentPassword, nextPassword)))
                    .andExpect(status().isNoContent());

            User stored = userRepository.findById(userId).orElseThrow();
            assertThat(stored.getPasswordHash()).isNotEqualTo(previousHash);
            assertThat(passwordEncoder.matches(nextPassword, stored.getPasswordHash())).isTrue();
            assertThat(passwordEncoder.matches(currentPassword, stored.getPasswordHash())).isFalse();
            currentPassword = nextPassword;
            previousHash = stored.getPasswordHash();
        }
    }

    @Test
    void overlong_change_password_is_bad_request_and_does_not_change_hash() throws Exception {
        String ascii73 = "aA1" + "x".repeat(70);
        String han75 = "aA1" + "汉".repeat(24);
        String emoji75 = "aA1" + "😀".repeat(18);
        assertThat(ascii73.getBytes(StandardCharsets.UTF_8)).hasSize(73);
        assertThat(han75.getBytes(StandardCharsets.UTF_8)).hasSize(75);
        assertThat(emoji75.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson(CURRENT_PASSWORD, ascii73)))
                .andExpect(status().isBadRequest());
        assertHashStillMatchesCurrentPassword();

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson(CURRENT_PASSWORD, han75)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码过长，请减少字符数量"));
        assertHashStillMatchesCurrentPassword();

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson(CURRENT_PASSWORD, emoji75)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码过长，请减少字符数量"));
        assertHashStillMatchesCurrentPassword();
    }

    @Test
    void current_password_accepts_suffix_after_72_byte_legacy_hash() throws Exception {
        String historicalPassword = ascii72();
        User user = userRepository.findById(userId).orElseThrow();
        user.setPasswordHash(passwordEncoder.encode(historicalPassword));
        user = userRepository.saveAndFlush(user);
        originalHash = user.getPasswordHash();

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson(historicalPassword + "legacy-suffix", NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        User stored = userRepository.findById(userId).orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo(originalHash);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash())).isTrue();
    }

    @Test
    void login_accepts_legacy_password_candidate_with_suffix_after_72_bytes() throws Exception {
        String historicalPassword = ascii72();
        User user = userRepository.findById(userId).orElseThrow();
        user.setPasswordHash(passwordEncoder.encode(historicalPassword));
        userRepository.saveAndFlush(user);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(user.getEmail(), historicalPassword + "legacy-suffix")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(user.getUsername()));
    }

    @Test
    void username_64_character_boundary_is_supported_for_registration_and_account_updates() throws Exception {
        String updatedUsername = "u".repeat(64);
        String registeredUsername = "r".repeat(64);
        String invalidUsername = "z".repeat(65);
        String basicSuffix = "\",\"phone\":\"13900000001\",\"email\":\"password-change-user@example.test\"}";

        mockMvc.perform(patch("/api/v1/users/me/basic")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + updatedUsername + basicSuffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(updatedUsername));
        assertThat(userRepository.findById(userId).orElseThrow().getUsername()).isEqualTo(updatedUsername);

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(registeredUsername, "username64@example.test", "13900000002", NEW_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(registeredUsername));
        User registered = userRepository.findByUsername(registeredUsername).orElseThrow();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, registered.getPasswordHash())).isTrue();

        mockMvc.perform(patch("/api/v1/users/me/basic")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + invalidUsername + basicSuffix))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(invalidUsername, "username65@example.test", "13900000003", NEW_PASSWORD)))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.findById(userId).orElseThrow().getUsername()).isEqualTo(updatedUsername);
        assertThat(userRepository.existsByUsername(invalidUsername)).isFalse();
    }

    private void assertHashStillMatchesCurrentPassword() {
        User stored = userRepository.findById(userId).orElseThrow();
        assertThat(stored.getPasswordHash()).isEqualTo(originalHash);
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, stored.getPasswordHash())).isTrue();
    }

    private String passwordChangeJson(String currentPassword, String newPassword) {
        return "{\"currentPassword\":\"" + currentPassword
                + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    private String registerJson(String username, String email, String phone, String password) {
        return "{\"username\":\"" + username
                + "\",\"phone\":\"" + phone
                + "\",\"email\":\"" + email
                + "\",\"password\":\"" + password + "\"}";
    }

    private String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private String ascii72() {
        return "aA1" + "x".repeat(69);
    }

    private String han72() {
        return "aA1" + "汉".repeat(23);
    }

    private String emoji72() {
        return "aA1" + "😀".repeat(17) + "x";
    }
}
