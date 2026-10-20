package com.example.demo.ai.summary.controller;

import com.example.demo.ai.service.AiQuotaService;
import com.example.demo.ai.summary.service.SummaryTaskService;
import com.example.demo.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SummaryControllerMissingHeaderTest {

    @Mock
    private SummaryTaskService tasks;

    @Mock
    private AiQuotaService quota;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SummaryController(tasks, quota))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missing_idempotency_key_is_bad_request_before_quota_or_task_services() throws Exception {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(42L, null);
        authentication.setAuthenticated(true);

        mockMvc.perform(post("/api/v1/ai/summary/17").principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));

        verifyNoInteractions(tasks, quota);
    }
}
