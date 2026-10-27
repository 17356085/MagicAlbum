package com.example.demo.user.connected.service.persistent;

import com.example.demo.user.connected.entity.UserConnectedAccount;
import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PersistentConnectedAccountsServiceTest {
    @Test
    void direct_connect_cannot_forge_authorized_account() {
        UserConnectedAccountRepository repository = mock(UserConnectedAccountRepository.class);
        PersistentConnectedAccountsService service = new PersistentConnectedAccountsService(repository);

        assertThatThrownBy(() -> service.connect(42L, "github"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.bindExternalAccount(42L, "github", "", "name"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void legacy_record_without_external_id_is_not_shown_as_connected() {
        UserConnectedAccountRepository repository = mock(UserConnectedAccountRepository.class);
        UserConnectedAccount oldRecord = new UserConnectedAccount();
        oldRecord.setProvider("github");
        oldRecord.setLinkedAt(Instant.now());
        when(repository.findByUserId(42L)).thenReturn(List.of(oldRecord));

        PersistentConnectedAccountsService service = new PersistentConnectedAccountsService(repository);
        assertThat(service.list(42L).get("items"))
                .asList().extracting("connected").containsOnly(false);
    }

    @Test
    void blank_provider_is_rejected_before_repository_access() {
        UserConnectedAccountRepository repository = mock(UserConnectedAccountRepository.class);
        PersistentConnectedAccountsService service = new PersistentConnectedAccountsService(repository);

        assertThatThrownBy(() -> service.bindExternalAccount(42L, null, "external", "name"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bindExternalAccount(42L, "  ", "external", "name"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void provider_keys_are_independent_of_default_locale() {
        UserConnectedAccountRepository repository = mock(UserConnectedAccountRepository.class);
        PersistentConnectedAccountsService service = new PersistentConnectedAccountsService(repository);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            service.bindExternalAccount(42L, " GITHUB ", "external", "name");
            service.findUserIdByExternalAccount(" GITHUB ", "external");
            service.disconnect(42L, " GITHUB ");
            verify(repository).findByUserIdAndProvider(42L, "github");
            verify(repository).findByProviderAndExternalId("github", "external");
            verify(repository).deleteByUserIdAndProvider(42L, "github");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
