package com.example.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorsConfigurationTest {

    @Test
    void configuredComposeOriginReachesControllerAndUnconfiguredOriginIsRejected() throws Exception {
        String allowedOrigin = "http://localhost:35173";
        SecurityConfig securityConfig = new SecurityConfig(
                allowedOrigin + ",http://127.0.0.1:35173");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ValidationController())
                .addFilters(new CorsFilter(securityConfig.corsConfigurationSource()))
                .build();

        mockMvc.perform(corsPost("/cors-validation", allowedOrigin))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin));
        mockMvc.perform(corsPost("/cors-validation", "http://localhost:45173"))
                .andExpect(status().isForbidden());
        mockMvc.perform(options("/cors-validation")
                        .header(HttpHeaders.ORIGIN, allowedOrigin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin));
    }

    @Test
    void defaultOriginsRemainAvailableAndKeepCorsMethodsAndCredentials() {
        SecurityConfig securityConfig = new SecurityConfig();
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        CorsConfiguration configuration = source.getCorsConfiguration(newRequest("/any"));

        assertEquals(List.of(
                "http://localhost:5073",
                "http://localhost:5173",
                "http://localhost:5174",
                "http://127.0.0.1:5073",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:5174"
        ), configuration.getAllowedOrigins());
        assertTrue(configuration.getAllowedMethods().contains("POST"));
        assertTrue(configuration.getAllowedMethods().contains("OPTIONS"));
        assertEquals(Boolean.TRUE, configuration.getAllowCredentials());
    }

    @Test
    void invalidConfiguredOriginsFailValidation() {
        for (String invalidOrigin : List.of(
                "*",
                "http://user:password@example.com",
                "http://example.com/path",
                "http://example.com?query=1",
                "http://example.com#fragment",
                "ftp://example.com",
                "http://example.com:65536",
                ""
        )) {
            assertThrows(IllegalStateException.class,
                    () -> new SecurityConfig(invalidOrigin).corsConfigurationSource(),
                    invalidOrigin);
        }
    }

    private static MockHttpServletRequestBuilder corsPost(String path, String origin) {
        return post(path).header(HttpHeaders.ORIGIN, origin);
    }

    private static org.springframework.mock.web.MockHttpServletRequest newRequest(String path) {
        return new org.springframework.mock.web.MockHttpServletRequest("GET", path);
    }

    @RestController
    static class ValidationController {

        @PostMapping("/cors-validation")
        ResponseEntity<Void> validate() {
            return ResponseEntity.badRequest().build();
        }
    }
}
