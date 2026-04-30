package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

/**
 * @author 17356
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthFilter) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
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
                        "/api/v1/threads/*/posts",
                        "/api/v1/ai/summary/*",
                        "/api/v1/system/config-demo"
                ).permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/uploads/**").permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许本地开发前端来源（Trae/IDEA dev server）
        config.setAllowedOrigins(List.of(
                "http://localhost:5073",
                "http://localhost:5173",
                "http://localhost:5174"
        ));
        // 允许常见方法
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // 允许请求头（Authorization 用于携带 JWT；Content-Type 用于 JSON）
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        // 若前端使用 withCredentials，需要允许凭证
        config.setAllowCredentials(true);
        // 可选：暴露部分响应头（如 Location 等）
        // config.setExposedHeaders(List.of("Location"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
