package com.example.demo.notifications.client;

import java.util.Locale;

/** Configuration validation only; randomness must be provided by the secret generator. */
public final class InternalServiceCredential {
    private InternalServiceCredential() {
    }

    public static String requireStrongConfiguration(String credential) {
        String value = credential == null ? "" : credential;
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.length() < 32 || value.length() > 256
                || value.chars().anyMatch(c -> c < 33 || c > 126)
                || value.chars().distinct().count() < 8
                || lower.contains("change-me") || lower.contains("change_me")
                || lower.contains("replace-me") || lower.contains("replace_me")
                || lower.contains("replace_with") || lower.contains("replace-with")
                || lower.contains("placeholder") || lower.contains("devsecret")) {
            throw new IllegalStateException("Internal service credential requires 32-256 printable ASCII characters without spaces; generate it from 32 random bytes");
        }
        return value;
    }
}
