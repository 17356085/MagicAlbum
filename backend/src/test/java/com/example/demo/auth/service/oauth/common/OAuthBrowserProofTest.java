package com.example.demo.auth.service.oauth.common;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OAuthBrowserProofTest {
    @Test void matchesTheRfc7636S256Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuthBrowserProof.challenge(
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test void rejectsMissingShortOversizedAndNonAsciiProofs() {
        for (String proof : new String[] {null, "", "x".repeat(42), "x".repeat(129), "中".repeat(43), "x ".repeat(30)}) {
            assertThrows(ResponseStatusException.class, () -> OAuthBrowserProof.challenge(proof));
        }
        for (String challenge : new String[] {null, "", "x".repeat(42), "x".repeat(44), "=".repeat(43)}) {
            assertThrows(ResponseStatusException.class, () -> OAuthBrowserProof.requireChallenge(challenge));
        }
    }
}
