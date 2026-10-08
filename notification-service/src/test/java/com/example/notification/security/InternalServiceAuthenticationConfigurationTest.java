package com.example.notification.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalServiceAuthenticationConfigurationTest {
    @Test
    void missing_credential_fails_configuration() {
        assertThrows(IllegalStateException.class, () -> new InternalServiceAuthenticationFilter(""));
    }

    @Test
    void blank_credential_fails_configuration() {
        assertThrows(IllegalStateException.class, () -> new InternalServiceAuthenticationFilter("   "));
    }

    @Test
    void placeholder_credential_fails_configuration() {
        assertThrows(IllegalStateException.class, () -> new InternalServiceAuthenticationFilter("change-me"));
        assertThrows(
                IllegalStateException.class,
                () -> new InternalServiceAuthenticationFilter("replace_with_a_secure_internal_service_token")
        );
    }
}
