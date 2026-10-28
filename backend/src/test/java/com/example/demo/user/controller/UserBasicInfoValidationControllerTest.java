package com.example.demo.user.controller;

import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.GlobalExceptionHandler;
import com.example.demo.user.dto.BasicInfoUpdateRequest;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserBasicInfoValidationControllerTest {
    private UserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        DistributedRateLimiter rateLimiter = mock(DistributedRateLimiter.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new UserAccountController(userService, rateLimiter))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of())
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overlong_email_is_rejected_before_update_service_is_called() throws Exception {
        String overlongEmail = emailOfLength(256);
        assertThat(overlongEmail).hasSize(256);

        mockMvc.perform(patch("/api/v1/users/me/basic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + overlongEmail + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("邮箱长度不能超过 255 个字符"));

        verify(userService, never()).updateBasicInfo(eq(7L), any(BasicInfoUpdateRequest.class));
    }

    @Test
    void email_at_255_character_boundary_is_allowed_and_forwarded_to_update_service() throws Exception {
        String email = emailOfLength(255);
        UserDto response = new UserDto();
        when(userService.updateBasicInfo(eq(7L), any(BasicInfoUpdateRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/api/v1/users/me/basic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());

        var request = org.mockito.ArgumentCaptor.forClass(BasicInfoUpdateRequest.class);
        verify(userService).updateBasicInfo(eq(7L), request.capture());
        assertThat(request.getValue().getEmail()).isEqualTo(email);
    }

    @Test
    void omitted_email_remains_optional_and_reaches_update_service() throws Exception {
        when(userService.updateBasicInfo(eq(7L), any(BasicInfoUpdateRequest.class))).thenReturn(new UserDto());

        mockMvc.perform(patch("/api/v1/users/me/basic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        var request = org.mockito.ArgumentCaptor.forClass(BasicInfoUpdateRequest.class);
        verify(userService).updateBasicInfo(eq(7L), request.capture());
        assertThat(request.getValue().getEmail()).isNull();
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
