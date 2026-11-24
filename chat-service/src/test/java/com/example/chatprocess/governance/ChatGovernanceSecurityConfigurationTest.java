package com.example.chatprocess.governance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChatGovernanceSecurityConfigurationTest {
    private static final String TOKEN = "governance-token-that-is-long-enough-for-tests-0123456789";

    @Test
    void accepts_exactly_one_matching_internal_header() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ChatGovernanceSecurityConfiguration.TOKEN_HEADER, TOKEN);

        assertThat(ChatGovernanceSecurityConfiguration.hasValidToken(request,
                TOKEN.getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void rejects_missing_wrong_and_duplicate_headers() {
        byte[] expected = TOKEN.getBytes(StandardCharsets.UTF_8);

        MockHttpServletRequest missing = new MockHttpServletRequest();
        assertThat(ChatGovernanceSecurityConfiguration.hasValidToken(missing, expected)).isFalse();

        MockHttpServletRequest wrong = new MockHttpServletRequest();
        wrong.addHeader(ChatGovernanceSecurityConfiguration.TOKEN_HEADER, "wrong-token");
        assertThat(ChatGovernanceSecurityConfiguration.hasValidToken(wrong, expected)).isFalse();

        MockHttpServletRequest duplicate = new MockHttpServletRequest();
        duplicate.addHeader(ChatGovernanceSecurityConfiguration.TOKEN_HEADER, TOKEN);
        duplicate.addHeader(ChatGovernanceSecurityConfiguration.TOKEN_HEADER, TOKEN);
        assertThat(ChatGovernanceSecurityConfiguration.hasValidToken(duplicate, expected)).isFalse();
    }
}
