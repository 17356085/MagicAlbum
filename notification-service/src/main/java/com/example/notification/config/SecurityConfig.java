package com.example.notification.config;

import com.example.notification.security.InternalServiceAuthenticationFilter;
import com.example.notification.security.JwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final String INTERNAL_PATH = "/api/v1/notifications/internal";
    private static final String NOTIFICATIONS_PATH = "/api/v1/notifications";

    @Bean
    public UserDetailsService userDetailsService() {
        // This service has no password-based login flow; all request identities come from the filters above.
        return username -> {
            throw new UsernameNotFoundException("Password authentication is not available");
        };
    }

    @Bean
    public FilterRegistrationBean<InternalServiceAuthenticationFilter> internalServiceFilterRegistration(
            InternalServiceAuthenticationFilter filter
    ) {
        FilterRegistrationBean<InternalServiceAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            InternalServiceAuthenticationFilter internalServiceAuthenticationFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(internalServiceAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, 401, "UNAUTHORIZED", "需要有效的认证"
                        ))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, 403, "FORBIDDEN", "无权访问该资源"
                        ))
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers(INTERNAL_PATH, INTERNAL_PATH + "/**")
                        .hasAuthority(InternalServiceAuthenticationFilter.INTERNAL_SERVICE_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, NOTIFICATIONS_PATH).authenticated()
                        .requestMatchers(HttpMethod.PATCH, NOTIFICATIONS_PATH + "/*/read").authenticated()
                        .anyRequest().denyAll()
                );
        return http.build();
    }

    private void writeError(
            jakarta.servlet.http.HttpServletResponse response,
            int status,
            String error,
            String message
    ) throws java.io.IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"status\":" + status + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\"}"
        );
    }
}
