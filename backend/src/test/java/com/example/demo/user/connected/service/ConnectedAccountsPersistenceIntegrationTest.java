package com.example.demo.user.connected.service;

import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import com.example.demo.user.connected.service.persistent.PersistentConnectedAccountsService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:connected_persistence;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false")
class ConnectedAccountsPersistenceIntegrationTest {
    @Autowired ConnectedAccountsService accounts;
    @Autowired UserConnectedAccountRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void selects_database_implementation_when_repository_exists() {
        assertThat(AopUtils.getTargetClass(accounts)).isEqualTo(PersistentConnectedAccountsService.class);
    }

    @Test
    void binding_is_persisted_and_rejected_disconnect_preserves_it() {
        accounts.bindExternalAccount(4200101L, "github", "connected-persist-a", "name");
        accounts.bindExternalAccount(4200102L, "github", "connected-persist-b", "other");
        assertThat(repository.findByProviderAndExternalId("github", "connected-persist-a")).isPresent();
        assertThatThrownBy(() -> accounts.disconnect(4200101L, "github"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(409));
        assertThat(repository.findByProviderAndExternalId("github", "connected-persist-a")).isPresent();
        assertThat(repository.findByProviderAndExternalId("github", "connected-persist-b")).isPresent();
    }

    @Test
    void binding_joins_caller_transaction_and_rolls_back() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accounts.bindExternalAccount(4200103L, "github", "connected-persist-rollback", "name");
            status.setRollbackOnly();
        });
        assertThat(repository.findByProviderAndExternalId("github", "connected-persist-rollback")).isEmpty();
        assertThat(accounts.findUserIdByExternalAccount("github", "connected-persist-rollback")).isNull();
    }
}
