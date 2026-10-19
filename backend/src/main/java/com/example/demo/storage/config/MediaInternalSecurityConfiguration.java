package com.example.demo.storage.config;

import com.example.demo.notifications.client.InternalServiceCredential;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
public class MediaInternalSecurityConfiguration {
    @Bean
    @Order(1)
    public SecurityFilterChain mediaInternalSecurity(HttpSecurity http,
            @Value("${app.media.processing-enabled:false}") boolean enabled,
            @Value("${app.media.internal-service-token:}") String token) throws Exception {
        byte[] expected = enabled ? InternalServiceCredential.requireStrongConfiguration(token)
                .getBytes(StandardCharsets.UTF_8) : new byte[0];
        http.securityMatcher("/internal/media/**")
                .csrf(csrf -> csrf.disable()).httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handler -> handler.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(headers -> headers.cacheControl(cache -> { }))
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) -> {
                    String presented = context.getRequest().getHeader("X-Internal-Service-Token");
                    return new AuthorizationDecision(enabled && presented != null && presented.length() <= 256
                            && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8)));
                }));
        return http.build();
    }
}
