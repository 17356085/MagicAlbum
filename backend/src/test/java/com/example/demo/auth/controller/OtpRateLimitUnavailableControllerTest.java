package com.example.demo.auth.controller;

import com.example.demo.auth.dto.AuthCodeStartRequest;
import com.example.demo.auth.service.AuthService;
import com.example.demo.auth.service.otp.CognitoOtpAuthService;
import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OtpRateLimitUnavailableControllerTest {
    private static final String RATE_LIMIT_MESSAGE = "限流服务暂不可用";
    private static final String RATE_LIMIT_SECRET = "redis-password=should-not-leak";
    private static final String OTP_SERVICE_MESSAGE = "邮箱验证码服务暂不可用，请稍后重试";

    private DistributedRateLimiter rateLimiter;
    private CognitoOtpAuthService cognitoOtpAuthService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthService authService = mock(AuthService.class);
        cognitoOtpAuthService = mock(CognitoOtpAuthService.class);
        rateLimiter = mock(DistributedRateLimiter.class);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService, cognitoOtpAuthService, rateLimiter))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void start_rate_limit_503_is_sanitized_and_otp_service_is_not_called() throws Exception {
        throwFromRateLimiter(HttpStatus.SERVICE_UNAVAILABLE, RATE_LIMIT_SECRET);

        mockMvc.perform(post("/api/v1/auth/code/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"email\",\"address\":\"user@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(RATE_LIMIT_MESSAGE))
                .andExpect(jsonPath("$.code").value("OTP_PROVIDER_NOT_CALLED"))
                .andExpect(content().string(not(containsString(RATE_LIMIT_SECRET))));

        verify(cognitoOtpAuthService, never()).start(any());
    }

    @Test
    void finish_rate_limit_503_is_sanitized_and_otp_service_is_not_called() throws Exception {
        throwFromRateLimiter(HttpStatus.SERVICE_UNAVAILABLE, RATE_LIMIT_SECRET);

        mockMvc.perform(post("/api/v1/auth/code/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"email\",\"address\":\"user@example.com\","
                                + "\"code\":\"123456\",\"session\":\"session\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(RATE_LIMIT_MESSAGE))
                .andExpect(jsonPath("$.code").value("OTP_PROVIDER_NOT_CALLED"))
                .andExpect(content().string(not(containsString(RATE_LIMIT_SECRET))));

        verify(cognitoOtpAuthService, never()).finish(any());
    }

    @Test
    void provider_503_keeps_existing_error_shape_without_not_called_code() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, OTP_SERVICE_MESSAGE))
                .when(cognitoOtpAuthService)
                .start(any());

        mockMvc.perform(post("/api/v1/auth/code/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"email\",\"address\":\"user@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(OTP_SERVICE_MESSAGE))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void service_503_keeps_existing_error_shape_without_not_called_code() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, OTP_SERVICE_MESSAGE))
                .when(cognitoOtpAuthService)
                .finish(any());

        mockMvc.perform(post("/api/v1/auth/code/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"email\",\"address\":\"user@example.com\","
                                + "\"code\":\"123456\",\"session\":\"session\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(OTP_SERVICE_MESSAGE))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void rate_limit_429_is_rethrown_without_not_called_code() throws Exception {
        throwFromRateLimiter(HttpStatus.TOO_MANY_REQUESTS, "too many requests");

        mockMvc.perform(post("/api/v1/auth/code/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"email\",\"address\":\"user@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("too many requests"))
                .andExpect(jsonPath("$.code").doesNotExist());

        verify(cognitoOtpAuthService, never()).start(any(AuthCodeStartRequest.class));
    }

    @Test
    void finish_rate_limit_429_is_rethrown_without_not_called_code() throws Exception {
        throwFromRateLimiter(HttpStatus.TOO_MANY_REQUESTS, "too many requests");

        mockMvc.perform(post("/api/v1/auth/code/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"email\",\"address\":\"user@example.com\","
                                + "\"code\":\"123456\",\"session\":\"session\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("too many requests"))
                .andExpect(jsonPath("$.code").doesNotExist());

        verify(cognitoOtpAuthService, never()).finish(any());
    }

    private void throwFromRateLimiter(HttpStatus status, String reason) {
        doThrow(new ResponseStatusException(status, reason))
                .when(rateLimiter)
                .enforce(anyString(), anyString(), anyInt(), anyInt());
    }
}
