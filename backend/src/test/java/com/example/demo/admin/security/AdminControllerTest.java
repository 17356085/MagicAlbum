package com.example.demo.admin.security;

import com.example.demo.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private AdminAccessService adminAccessService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(adminAccessService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void me_returns_only_the_minimal_admin_flag_for_the_current_principal() throws Exception {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(42L, null);
        authentication.setAuthenticated(true);

        mockMvc.perform(get("/api/v1/admin/me").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"admin\":true}"));

        verify(adminAccessService).requireAdmin(42L);
    }

    @Test
    void me_requires_an_authenticated_numeric_jwt_principal() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"))
                .when(adminAccessService)
                .requireAdmin((Long) null);

        mockMvc.perform(get("/api/v1/admin/me"))
                .andExpect(status().isUnauthorized());

        verify(adminAccessService).requireAdmin(null);
    }
}
