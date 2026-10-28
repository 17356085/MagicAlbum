package com.example.demo.user.controller;

import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.GlobalExceptionHandler;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserRegistrationEmailValidationControllerTest {
    private UserService userService;
    private DistributedRateLimiter rateLimiter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        rateLimiter = mock(DistributedRateLimiter.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new UserAccountController(userService, rateLimiter))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        mockMvc = null;
    }

    @Test
    void overlong_email_is_rejected_before_rate_limiter_and_register_service() throws Exception {
        String email = emailOfLength(256);
        assertThat(email).hasSize(256);

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("邮箱长度不能超过 255 个字符"));

        verifyNoInteractions(rateLimiter, userService);
    }

    @Test
    void email_at_255_character_boundary_reaches_register_service() throws Exception {
        String email = emailOfLength(255);
        UserDto created = new UserDto();
        when(userService.register(any())).thenReturn(created);

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isCreated());

        verify(rateLimiter).enforce("register-ip", "127.0.0.1", 600, 12);
        verify(rateLimiter).enforce("register-device", "127.0.0.1", 600, 8);
        verify(rateLimiter).enforce("register-account", email + ":13800138000", 900, 3);
        verify(userService).register(any());
    }

    @Test
    void missing_email_is_rejected_before_rate_limiter_and_register_service() throws Exception {
        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJsonWithoutEmail()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rateLimiter, userService);
    }

    private static String registerJson(String email) {
        return """
                {
                  "username": "registration-email-user",
                  "phone": "13800138000",
                  "email": "%s",
                  "password": "Password1"
                }
                """.formatted(email);
    }

    private static String registerJsonWithoutEmail() {
        return """
                {
                  "username": "registration-email-user",
                  "phone": "13800138000",
                  "password": "Password1"
                }
                """;
    }

    private static String emailOfLength(int length) {
        int localPartLength = length == 255 ? 63 : 64;
        return "a".repeat(localPartLength)
                + "@"
                + "b".repeat(63)
                + "."
                + "c".repeat(63)
                + "."
                + "d".repeat(63);
    }
}
