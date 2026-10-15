package com.example.demo.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author 17356
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String DEFAULT_ALLOWED_ORIGINS =
            "http://localhost:5073,http://localhost:5173,http://localhost:5174," +
                    "http://127.0.0.1:5073,http://127.0.0.1:5173,http://127.0.0.1:5174";

    private static final Pattern VALID_ORIGIN = Pattern.compile(
            "^(https?)://((?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)*"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]+\\])"
                    + "(?::([1-9][0-9]{0,4}))?$");

    @Value("${app.cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}")
    private String configuredAllowedOrigins = DEFAULT_ALLOWED_ORIGINS;

    public SecurityConfig() {
    }

    SecurityConfig(String allowedOrigins) {
        this.configuredAllowedOrigins = allowedOrigins;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Spring-managed password authentication is disabled");
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthFilter) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // The initial REQUEST is authenticated below; SSE completion is a server dispatch.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/auth/code/start",
                        "/api/v1/auth/code/finish",
                        "/api/v1/auth/oauth/authorize",
                        "/api/v1/auth/oauth/callback/**",
                        "/api/v1/auth/oauth/exchange",
                        "/api/v1/auth/qr",
                        "/api/v1/auth/qr/status",
                        "/api/v1/auth/qr/cancel",
                        "/api/v1/auth/qr/exchange"
                ).permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/users/register", "/api/v1/users/availability").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,
                        "/api/v1/users",
                        "/api/v1/users/*/profile",
                        "/api/v1/users/*/threads",
                        "/api/v1/users/*/followers",
                        "/api/v1/users/*/following",
                        "/api/v1/sections",
                        "/api/v1/tags",
                        "/api/v1/tags/popular",
                        "/api/v1/threads",
                        "/api/v1/threads/*",
                        "/api/v1/threads/*/posts"
                ).permitAll()
                .requestMatchers("/api/v1/ai/**").authenticated()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/uploads/**").permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(parseAllowedOrigins(configuredAllowedOrigins));
        // 允许常见方法
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // 允许请求头（Authorization 用于携带 JWT；Content-Type 用于 JSON）
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-Device-Id", "X-Session-Id", "X-Request-Id"));
        // 若前端使用 withCredentials，需要允许凭证
        config.setAllowCredentials(true);
        // 可选：暴露部分响应头（如 Location 等）
        // config.setExposedHeaders(List.of("Location"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static List<String> parseAllowedOrigins(String rawOrigins) {
        if (rawOrigins == null || rawOrigins.isBlank()) {
            throw new IllegalStateException("app.cors.allowed-origins must contain at least one origin");
        }
        return Arrays.stream(rawOrigins.split(",", -1))
                .map(String::trim)
                .map(SecurityConfig::validateOrigin)
                .toList();
    }

    private static String validateOrigin(String origin) {
        Matcher matcher = VALID_ORIGIN.matcher(origin);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins contains an invalid HTTP origin: " + origin);
        }
        String portValue = matcher.group(3);
        if (portValue != null && Integer.parseInt(portValue) > 65535) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins contains an invalid port: " + origin);
        }
        return origin;
    }
}
