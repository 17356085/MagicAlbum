package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.AuthCodeChannel;
import com.example.demo.auth.dto.AuthCodeFinishRequest;
import com.example.demo.auth.dto.CognitoAuthFlowResponse;
import com.example.demo.auth.dto.CognitoAuthenticationResult;
import com.example.demo.auth.dto.LoginRequest;
import com.example.demo.auth.dto.github.GithubUserProfile;
import com.example.demo.auth.service.AuthService;
import com.example.demo.auth.service.LocalPasswordAuthenticator;
import com.example.demo.auth.service.otp.AuthVerifyService;
import com.example.demo.auth.service.otp.CognitoOtpAuthService;
import com.example.demo.auth.service.otp.CognitoOtpClient;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import com.example.demo.user.dto.RegisterRequest;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.service.UserService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses a random in-memory database and a simulated OTP provider; no external identities are contacted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.rabbit.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class UnassignedContactProvisioningIntegrationTest {
    private static final String DATABASE_NAME = "unassigned_contact_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired private UserRepository users;
    @Autowired private UserProfileRepository profiles;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider realTokens;
    @Autowired private Validator validator;
    private PasswordEncoder passwords;
    private AuthVerifyService verify;
    private JwtTokenProvider tokens;
    private CognitoOtpClient otpClient;
    private UserService userService;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE_NAME
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false");
    }

    @BeforeEach
    void setUp() {
        passwords = mock(PasswordEncoder.class);
        verify = mock(AuthVerifyService.class);
        tokens = mock(JwtTokenProvider.class);
        otpClient = mock(CognitoOtpClient.class);
        when(passwords.encode(anyString())).thenReturn("test-only-encoded-password");
        when(tokens.generateToken(anyLong(), anyString())).thenReturn("test-only-token");
        userService = new UserService(users, profiles, passwords, verify);
    }

    @Test
    void emailOtpAccountDoesNotReserveAnotherPersonsNumber() {
        UserDto emailUser = provisionEmail("demo@example.com");

        UserDto phoneOwner = register("email_collision_owner", "owner1@example.test", "19821864416");

        assertThat(emailUser.getPhone()).isNull();
        assertThat(phoneOwner.getId()).isNotEqualTo(emailUser.getId());
        assertThat(users.findByPhone("19821864416")).get().extracting(User::getId).isEqualTo(phoneOwner.getId());
        when(passwords.matches("TestPassword1", "test-only-encoded-password")).thenReturn(true);
        LoginRequest login = new LoginRequest();
        login.setPhone("19821864416");
        login.setPassword("TestPassword1");
        assertThat(new AuthService(new LocalPasswordAuthenticator(users, passwords), tokens, verify).login(login).getUser().getId())
                .isEqualTo(phoneOwner.getId());
    }

    @Test
    void githubAccountDoesNotReserveAnotherPersonsNumber() {
        User oauthUser = provisionGithub();

        UserDto phoneOwner = register("oauth_collision_owner", "owner2@example.test", "19900012345");

        assertThat(oauthUser.getPhone()).isNull();
        assertThat(phoneOwner.getId()).isNotEqualTo(oauthUser.getId());
        assertThat(users.findByPhone("19900012345")).get().extracting(User::getId).isEqualTo(phoneOwner.getId());
    }

    @Test
    void distinctNewAccountsCanBothHaveNoPhone() {
        UserDto emailUser = provisionEmail("demo@example.com");
        User oauthUser = provisionGithub();
        users.flush();

        assertThat(emailUser.getPhone()).isNull();
        assertThat(oauthUser.getPhone()).isNull();
        assertThat(emailUser.getId()).isNotEqualTo(oauthUser.getId());
    }

    @Test
    void phoneOtpAccountDoesNotReserveAnotherPersonsEmail() {
        UserDto phoneUser = provisionPhone("13800138000");

        UserDto emailOwner = register("email_owner", "phone_13800138000@otp.local", "13900139000");

        assertThat(phoneUser.getEmail()).isNull();
        assertThat(emailOwner.getId()).isNotEqualTo(phoneUser.getId());
        assertThat(users.findByEmail("phone_13800138000@otp.local"))
                .get().extracting(User::getId).isEqualTo(emailOwner.getId());
    }

    @Test
    void phoneOtpDoesNotMergeWithAnAccountUsingTheOldSyntheticEmail() {
        UserDto existing = register("existing_email_owner", "phone_13800138000@otp.local", "13900139000");

        UserDto phoneUser = provisionPhone("13800138000");

        assertThat(phoneUser.getId()).isNotEqualTo(existing.getId());
        assertThat(phoneUser.getEmail()).isNull();
        assertThat(users.findByEmail("phone_13800138000@otp.local"))
                .get().extracting(User::getId).isEqualTo(existing.getId());
        assertThat(users.findByPhone("13800138000")).get().extracting(User::getId).isEqualTo(phoneUser.getId());
    }

    @Test
    void githubAccountDoesNotPresentAnUnverifiedProviderEmailAsAContact() {
        User oauthUser = provisionGithub();

        assertThat(oauthUser.getEmail()).isNull();
        assertThat(oauthUser.isEmailVerified()).isFalse();
    }

    @Test
    void distinctNewAccountsCanBothHaveNoEmail() {
        UserDto phoneUser = provisionPhone("13800138000");
        User oauthUser = provisionGithub();
        users.flush();

        assertThat(phoneUser.getEmail()).isNull();
        assertThat(oauthUser.getEmail()).isNull();
        assertThat(phoneUser.getId()).isNotEqualTo(oauthUser.getId());
    }

    @Test
    void accountApiReadsAndUpdatesAnUnassignedPhoneWithoutClearingARealOne() throws Exception {
        UserDto emailUser = provisionEmail("demo@example.com");
        String token = realTokens.generateToken(emailUser.getId(), emailUser.getUsername());

        mockMvc.perform(get("/api/v1/users/me/basic").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value(nullValue()));
        mockMvc.perform(patch("/api/v1/users/me/basic").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"renamed_user\",\"email\":\"demo@example.com\",\"phone\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value(nullValue()));
        users.flush();
        assertThat(users.findById(emailUser.getId()).orElseThrow().getPhone()).isNull();

        mockMvc.perform(patch("/api/v1/users/me/basic").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"13800138000\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("13800138000"));
        mockMvc.perform(patch("/api/v1/users/me/basic").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("13800138000"));
        mockMvc.perform(patch("/api/v1/users/me/basic").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"\"}"))
                .andExpect(status().isBadRequest());
        users.flush();
        User stored = users.findById(emailUser.getId()).orElseThrow();
        assertThat(stored.getPhone()).isEqualTo("13800138000");
        assertThat(stored.isEmailVerified()).isTrue();
        assertThat(stored.isPhoneVerified()).isFalse();
    }

    @Test
    void accountApiHandlesTwoAbsentContactsAndDoesNotClearANewEmail() throws Exception {
        User oauthUser = provisionGithub();
        String token = realTokens.generateToken(oauthUser.getId(), oauthUser.getUsername());

        mockMvc.perform(get("/api/v1/users/me/basic").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.email").value(nullValue()));
        mockMvc.perform(patch("/api/v1/users/me/basic").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"renamed_user\",\"email\":null,\"phone\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.phone").value(nullValue()));
        users.flush();
        User missingContacts = users.findById(oauthUser.getId()).orElseThrow();
        assertThat(missingContacts.getEmail()).isNull();
        assertThat(missingContacts.getPhone()).isNull();

        mockMvc.perform(patch("/api/v1/users/me/basic").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"new@example.test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("new@example.test"));
        mockMvc.perform(patch("/api/v1/users/me/basic").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("new@example.test"));
        mockMvc.perform(patch("/api/v1/users/me/basic").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest());
        users.flush();
        User stored = users.findById(oauthUser.getId()).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo("new@example.test");
        assertThat(stored.isEmailVerified()).isFalse();
    }

    private UserDto provisionEmail(String email) {
        CognitoAuthenticationResult result = new CognitoAuthenticationResult();
        result.setAccessToken("test-only-provider-token");
        CognitoAuthFlowResponse verified = new CognitoAuthFlowResponse();
        verified.setAuthenticationResult(result);
        when(otpClient.respondToChallenge("EMAIL_OTP", email, Map.of("EMAIL_OTP_CODE", "123456"), "session"))
                .thenReturn(verified);
        CognitoOtpAuthService service = new CognitoOtpAuthService(
                otpClient, users, profiles, passwords, tokens, verify, 300, 300, 60);
        AuthCodeFinishRequest request = new AuthCodeFinishRequest();
        request.setChannel(AuthCodeChannel.email);
        request.setAddress(email);
        request.setCode("123456");
        request.setSession("session");
        return service.finish(request).getUser();
    }

    private UserDto provisionPhone(String phone) {
        CognitoAuthenticationResult result = new CognitoAuthenticationResult();
        result.setAccessToken("test-only-provider-token");
        CognitoAuthFlowResponse verified = new CognitoAuthFlowResponse();
        verified.setAuthenticationResult(result);
        when(otpClient.respondToChallenge("SMS_OTP", phone, Map.of("SMS_OTP_CODE", "123456"), "session"))
                .thenReturn(verified);
        CognitoOtpAuthService service = new CognitoOtpAuthService(
                otpClient, users, profiles, passwords, tokens, verify, 300, 300, 60);
        AuthCodeFinishRequest request = new AuthCodeFinishRequest();
        request.setChannel(AuthCodeChannel.phone);
        request.setAddress(phone);
        request.setCode("123456");
        request.setSession("session");
        return service.finish(request).getUser();
    }

    private User provisionGithub() {
        ConnectedAccountsService accounts = mock(ConnectedAccountsService.class);
        when(accounts.findUserIdByExternalAccount(anyString(), anyString())).thenReturn(null);
        OAuthUserProvisioningService service = OAuthUserProvisioningService.forTest(users, profiles, accounts, passwords);
        GithubUserProfile profile = new GithubUserProfile();
        profile.setId(12345L);
        profile.setLogin("github-test-user");
        return service.resolveOrCreateGithubUser(profile);
    }

    private UserDto register(String username, String email, String phone) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPhone(phone);
        request.setPassword("TestPassword1");
        assertThat(validator.validate(request)).isEmpty();
        return userService.register(request);
    }
}
