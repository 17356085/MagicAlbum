package com.example.notification.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenServiceConfigurationTest {
    @Test
    void missing_secret_fails_configuration() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenService("", "notification"));
    }

    @Test
    void weak_secret_fails_configuration() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenService("short", "notification"));
    }

    @Test
    void missing_issuer_fails_configuration() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenService(strongSecret(), ""));
    }

    @Test
    void weak_issuer_fails_configuration() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenService(strongSecret(), "x"));
    }

    private String strongSecret() {
        return "test-" + UUID.randomUUID() + "-" + UUID.randomUUID();
    }
}
