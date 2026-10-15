package com.example.demo.notifications.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalServiceCredentialTest {
    @Test
    void rejects_missing_short_repeated_and_placeholder_values() {
        for (String value : new String[]{null, "short", "a".repeat(64),
                "replace_with_a_secure_internal_service_token", "abcdef0123456789".repeat(2) + " "}) {
            assertThatThrownBy(() -> InternalServiceCredential.requireStrongConfiguration(value))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void accepts_generated_value_without_logging_the_credential() {
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        String value = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        assertThat(InternalServiceCredential.requireStrongConfiguration(value).length()).isEqualTo(43);
    }
}
