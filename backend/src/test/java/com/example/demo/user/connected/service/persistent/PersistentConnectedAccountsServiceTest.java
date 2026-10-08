package com.example.demo.user.connected.service.persistent;

import com.example.demo.user.connected.entity.UserConnectedAccount;
import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

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
}
