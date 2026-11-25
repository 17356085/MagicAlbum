package com.example.chatprocess.security;

import com.example.demo.chat.remote.RemoteChatDirectory;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/** Security boundary for the isolated process; no core JWT filter is imported. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class ChatProcessSecurityConfiguration {
    private static final String DEFAULT_ALLOWED_ORIGINS =
            "http://localhost:5073,http://localhost:5173,http://localhost:5174,"
                    + "http://127.0.0.1:5073,http://127.0.0.1:5173,http://127.0.0.1:5174";

    @Bean
    public RemoteBearerAuthenticationFilter remoteBearerAuthenticationFilter(RemoteChatDirectory directory) {
        return new RemoteBearerAuthenticationFilter(directory);
    }

    /** Security owns the filter chain; servlet auto-registration would run it twice. */
    @Bean
    public FilterRegistrationBean<RemoteBearerAuthenticationFilter> remoteBearerAuthenticationFilterRegistration(
            RemoteBearerAuthenticationFilter filter) {
        FilterRegistrationBean<RemoteBearerAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(0)
    public SecurityFilterChain chatProcessSecurity(HttpSecurity http,
            RemoteBearerAuthenticationFilter authenticationFilter,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
        http.cors(cors -> cors.configurationSource(corsSource))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ignored) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, "请先登录"))
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers("/api/v1/chat/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",", -1))
                .map(String::trim).filter(value -> !value.isBlank()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-Request-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ignored) -> writeError(response, HttpStatus.FORBIDDEN, "无权访问");
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String message) throws java.io.IOException {
        if (response.isCommitted()) return;
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"status\":" + status.value() + ",\"message\":\"" + message + "\"}");
    }
}
