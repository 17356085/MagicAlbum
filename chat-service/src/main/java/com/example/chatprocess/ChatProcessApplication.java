package com.example.chatprocess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

import java.util.Map;

/**
 * Independently built chat process. Only process-local components are scanned.
 */
@SpringBootApplication(
        scanBasePackages = "com.example.chatprocess",
        exclude = UserDetailsServiceAutoConfiguration.class)
public class ChatProcessApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ChatProcessApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", "chat-service"));
        application.run(args);
    }
}
