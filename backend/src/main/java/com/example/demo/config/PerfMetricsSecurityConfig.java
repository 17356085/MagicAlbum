package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Metrics are scraped only within the isolated performance environment. */
@Configuration
@Profile("perf")
public class PerfMetricsSecurityConfig {
    @Bean
    @Order(0)
    SecurityFilterChain perfMetricsSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/actuator/prometheus")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
