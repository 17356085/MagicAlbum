package com.example.notification.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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

    @Test
    void short_repeated_whitespace_and_non_ascii_credentials_fail_configuration() {
        for (String value : new String[]{"0123456789abcdef", "a".repeat(64), "01234567".repeat(5) + " ",
                "0123456789abcdef".repeat(20), "密钥".repeat(32), "0123456789abcdef".repeat(2) + "\nextra"}) {
            assertThrows(IllegalStateException.class, () -> new InternalServiceAuthenticationFilter(value));
        }
    }

    @Test
    void generated_credentials_are_accepted_without_exposing_them() {
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        String value = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        assertDoesNotThrow(() -> new InternalServiceAuthenticationFilter(value));
    }
}
