package com.example.demo.user.connected.service;

import com.example.demo.user.connected.entity.UserConnectedAccount;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectedAccountBindingTagTest {
    @Test
    void marker_is_stable_but_changes_with_binding_identity_or_key() {
        ConnectedAccountBindingTag tags = new ConnectedAccountBindingTag("test-only-secret-with-at-least-32-bytes");
        UserConnectedAccount account = account(11L, 7L, "github", "provider-subject");
        String tag = tags.forBinding(account);

        assertThat(tag).matches("[0-9a-f]{64}").doesNotContain("provider-subject");
        assertThat(tags.matches(account, tag)).isTrue();
        assertThat(tags.forBinding(account(11L, 7L, "github", "provider-subject"))).isEqualTo(tag);
        assertThat(tags.forBinding(account(12L, 7L, "github", "provider-subject"))).isNotEqualTo(tag);
        assertThat(tags.forBinding(account(11L, 8L, "github", "provider-subject"))).isNotEqualTo(tag);
        assertThat(tags.forBinding(account(11L, 7L, "github", "other-subject"))).isNotEqualTo(tag);
        assertThat(new ConnectedAccountBindingTag("different-test-secret-with-32-bytes").matches(account, tag)).isFalse();
        assertThat(tags.matches(account, "not-a-tag")).isFalse();
    }

    private UserConnectedAccount account(long id, long userId, String provider, String externalId) {
        UserConnectedAccount account = new UserConnectedAccount();
        account.setId(id);
        account.setUserId(userId);
        account.setProvider(provider);
        account.setExternalId(externalId);
        account.setLinkedAt(Instant.now());
        return account;
    }
}
