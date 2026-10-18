package com.example.demo.user.controller;

import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.GlobalExceptionHandler;
import com.example.demo.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.SQLException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserPhoneConflictControllerTest {
    private static final String PHONE = "13800138000";
    private UserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        DistributedRateLimiter rateLimiter = mock(DistributedRateLimiter.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserAccountController(userService, rateLimiter))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registration_race_is_a_sanitized_409() throws Exception {
        when(userService.register(any())).thenThrow(phoneConflict());

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "phone-race-user",
                                  "phone": "%s",
                                  "email": "phone-race@example.test",
                                  "password": "Password1"
                                }
                                """.formatted(PHONE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("该手机号已被占用"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PHONE))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("users_phone_uq"))));
    }

    @Test
    void basic_info_update_race_is_a_sanitized_409() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of())
        );
        when(userService.updateBasicInfo(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenThrow(phoneConflict());

        mockMvc.perform(patch("/api/v1/users/me/basic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("该手机号已被占用"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PHONE))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("users_phone_uq"))));
    }

    private DataIntegrityViolationException phoneConflict() {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("Duplicate entry '" + PHONE + "' for key 'users_phone_uq'")
        );
    }
}
