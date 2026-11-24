package com.example.chatprocess.governance;

import com.example.demo.notifications.client.InternalServiceCredential;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

/** Earlier, exact private chain for core-owned chat moderation delivery. */
@Configuration(proxyBeanMethods = false)
public class ChatGovernanceSecurityConfiguration {
    static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String[] PATHS = {
            "/internal/v1/chat/report-access",
            "/internal/v1/chat/report-evidence",
            "/internal/v1/chat/moderation-decisions"
    };

    @Bean
    @Order(-1)
    public SecurityFilterChain chatGovernanceSecurity(HttpSecurity http,
            @Value("${app.chat.governance.token:${CHAT_GOVERNANCE_INTERNAL_TOKEN:}}") String governanceToken,
            @Value("${app.chat.internal-service-token:${CHAT_INTERNAL_SERVICE_TOKEN:}}") String serviceToken)
            throws Exception {
        boolean enabled = governanceToken != null && !governanceToken.isBlank();
        byte[] expected = enabled
                ? InternalServiceCredential.requireStrongConfiguration(governanceToken)
                .getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (enabled && governanceToken.equals(serviceToken)) {
            throw new IllegalStateException("Chat governance requires a credential distinct from chat service access");
        }
        HeaderWriter noStore = (request, response) -> response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return http.securityMatcher("/internal/v1/chat/**")
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handler -> handler.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(headers -> headers.addHeaderWriter(noStore))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PATHS)
                        .access((authentication, context) -> new AuthorizationDecision(
                                enabled && hasValidToken(context.getRequest(), expected)))
                        .anyRequest().denyAll())
                .build();
    }

    static boolean hasValidToken(HttpServletRequest request, byte[] expected) {
        var presented = Collections.list(request.getHeaders(TOKEN_HEADER));
        return presented.size() == 1 && presented.getFirst().length() <= 256
                && MessageDigest.isEqual(expected, presented.getFirst().getBytes(StandardCharsets.UTF_8));
    }
}
