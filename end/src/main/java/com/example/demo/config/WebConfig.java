package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Value("${app.storage.local.baseDir:uploads}")
    private String localBaseDir;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:5174")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        // 统一为绝对路径的 file: URL（Windows 与 *nix 兼容）
        java.nio.file.Path abs = java.nio.file.Paths.get(localBaseDir).toAbsolutePath();
        String fileUrl = abs.toUri().toString(); // e.g. file:/C:/.../uploads/
        if (!fileUrl.endsWith("/")) fileUrl = fileUrl + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(fileUrl);
    }
}