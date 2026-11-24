package com.example.demo.config;

import com.example.demo.notifications.client.InternalServiceCredential;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

@Configuration
public class ChatInternalSecurityConfig {
    private static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String[] CHAT_CONTRACTS = {
        "/internal/v1/social/contact-decisions", "/internal/v1/social/contact-decisions:batchGet",
        "/internal/v1/users/public-profiles:batchGet"
    };

    @Bean
    @Order(2)
    SecurityFilterChain chatInternalSecurity(HttpSecurity http,
            @Value("${app.chat.internal-service-token:}") String token,
            @Value("${app.notifications.client.internal-service-token:}") String notificationToken,
            @Value("${app.media.internal-service-token:}") String mediaToken) throws Exception {
        boolean enabled = !token.isEmpty();
        byte[] expected = enabled ? InternalServiceCredential.requireStrongConfiguration(token)
                .getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (enabled && (token.equals(notificationToken) || token.equals(mediaToken))) {
            throw new IllegalStateException("The chat internal service requires its own credential");
        }
        return http.securityMatcher(CHAT_CONTRACTS)
                .csrf(csrf -> csrf.disable()).httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handler -> handler.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, CHAT_CONTRACTS)
                        .access((authentication, context) -> {
                            var headers = Collections.list(context.getRequest().getHeaders(TOKEN_HEADER));
                            return new AuthorizationDecision(enabled && headers.size() == 1 && headers.getFirst().length() <= 256
                                    && MessageDigest.isEqual(expected, headers.getFirst().getBytes(StandardCharsets.UTF_8)));
                        })
                        .anyRequest().denyAll())
                .build();
    }
}
