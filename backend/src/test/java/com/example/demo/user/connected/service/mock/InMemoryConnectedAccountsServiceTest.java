package com.example.demo.user.connected.service.mock;

import com.example.demo.user.connected.dto.ConnectedAccountDto;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryConnectedAccountsServiceTest {
    @Test
    void bind_then_lookup_and_list_report_one_authorized_account() {
        InMemoryConnectedAccountsService service = new InMemoryConnectedAccountsService();

        service.bindExternalAccount(42L, "github", "gh-42", "Alice");

        assertThat(service.findUserIdByExternalAccount("github", "gh-42")).isEqualTo(42L);
        ConnectedAccountDto item = findItem(service.list(42L), "github");
        assertThat(item).isNotNull();
        assertThat(item.isConnected()).isTrue();
        assertThat(item.getDisplayName()).isEqualTo("Alice");
        assertThat(items(service.list(42L)).stream()
                .filter(candidate -> "github".equals(candidate.getProvider())))
                .hasSize(1);
    }

    @Test
    void disconnect_removes_lookup_binding_and_marks_list_item_disconnected() {
        InMemoryConnectedAccountsService service = new InMemoryConnectedAccountsService();
        service.bindExternalAccount(42L, "github", "gh-42", "Alice");

        service.disconnect(42L, "github");

        assertThat(service.findUserIdByExternalAccount("github", "gh-42")).isNull();
        ConnectedAccountDto item = findItem(service.list(42L), "github");
        assertThat(item).isNotNull();
        assertThat(item.isConnected()).isFalse();
        assertThat(item.getDisplayName()).isNull();
    }

    @Test
    void rebinding_same_owner_and_provider_removes_old_lookup_binding() {
        InMemoryConnectedAccountsService service = new InMemoryConnectedAccountsService();
        service.bindExternalAccount(42L, "github", "old-gh-42", "Old Alice");

        service.bindExternalAccount(42L, "github", "new-gh-42", "New Alice");

        assertThat(service.findUserIdByExternalAccount("github", "old-gh-42")).isNull();
        assertThat(service.findUserIdByExternalAccount("github", "new-gh-42")).isEqualTo(42L);
        ConnectedAccountDto item = findItem(service.list(42L), "github");
        assertThat(item).isNotNull();
        assertThat(item.isConnected()).isTrue();
        assertThat(item.getDisplayName()).isEqualTo("New Alice");
    }

    @Test
    void conflicting_owner_bind_is_rejected_without_changing_either_owner() {
        InMemoryConnectedAccountsService service = new InMemoryConnectedAccountsService();
        service.bindExternalAccount(100L, "github", "same-gh", "Alice");

        assertThatThrownBy(() -> service.bindExternalAccount(200L, " GITHUB ", "same-gh", "Bob"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(service.findUserIdByExternalAccount("github", "same-gh")).isEqualTo(100L);
        ConnectedAccountDto original = findItem(service.list(100L), "github");
        assertThat(original).isNotNull();
        assertThat(original.isConnected()).isTrue();
        assertThat(original.getDisplayName()).isEqualTo("Alice");
        ConnectedAccountDto rejected = findItem(service.list(200L), "github");
        assertThat(rejected).isNotNull();
        assertThat(rejected.isConnected()).isFalse();
    }

    @Test
    void conflicting_bind_preserves_other_owner_bindings_and_disconnect_releases_external_id() {
        InMemoryConnectedAccountsService service = new InMemoryConnectedAccountsService();
        service.bindExternalAccount(100L, "github", "same-gh", "Alice");
        service.bindExternalAccount(100L, "google", "google-100", "Alice Google");
        service.bindExternalAccount(200L, "github", "bob-old-gh", "Bob Before");

        assertThatThrownBy(() -> service.bindExternalAccount(200L, "github", "same-gh", "Bob"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(findItem(service.list(100L), "github").getDisplayName()).isEqualTo("Alice");
        assertThat(findItem(service.list(100L), "google").getDisplayName()).isEqualTo("Alice Google");
        assertThat(service.findUserIdByExternalAccount("github", "same-gh")).isEqualTo(100L);
        assertThat(service.findUserIdByExternalAccount("github", "bob-old-gh")).isEqualTo(200L);
        assertThat(findItem(service.list(200L), "github").getDisplayName()).isEqualTo("Bob Before");

        service.disconnect(100L, "github");
        service.bindExternalAccount(200L, "github", "same-gh", "Bob");

        assertThat(service.findUserIdByExternalAccount("github", "same-gh")).isEqualTo(200L);
        assertThat(service.findUserIdByExternalAccount("github", "bob-old-gh")).isNull();
        assertThat(findItem(service.list(100L), "github").isConnected()).isFalse();
        assertThat(findItem(service.list(100L), "google").isConnected()).isTrue();
        assertThat(findItem(service.list(200L), "github").getDisplayName()).isEqualTo("Bob");
    }

    @Test
    void provider_and_external_id_normalization_is_used_by_lookup_list_and_disconnect() {
        InMemoryConnectedAccountsService service = new InMemoryConnectedAccountsService();

        service.bindExternalAccount(42L, "  GITHUB  ", "  gh-42  ", "Alice");

        assertThat(service.findUserIdByExternalAccount("github", "gh-42")).isEqualTo(42L);
        assertThat(service.findUserIdByExternalAccount(" GITHUB ", " gh-42 ")).isEqualTo(42L);
        List<ConnectedAccountDto> githubItems = items(service.list(42L)).stream()
                .filter(item -> "github".equals(item.getProvider()))
                .toList();
        assertThat(githubItems).hasSize(1);
        assertThat(githubItems.get(0).isConnected()).isTrue();

        service.disconnect(42L, " GITHUB ");

        assertThat(service.findUserIdByExternalAccount("github", "gh-42")).isNull();
        assertThat(findItem(service.list(42L), "github").isConnected()).isFalse();
    }

    @Test
    void null_or_blank_provider_is_rejected_without_changing_public_state() {
        InMemoryConnectedAccountsService service = new InMemoryConnectedAccountsService();
        service.bindExternalAccount(42L, "github", "gh-42", "Alice");

        assertThatThrownBy(() -> service.bindExternalAccount(42L, "  ", "blank-provider", "Ignored"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bindExternalAccount(42L, null, "null-provider", "Ignored"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.findUserIdByExternalAccount("", "blank-provider")).isNull();
        assertThat(service.findUserIdByExternalAccount("github", "gh-42")).isEqualTo(42L);
        assertThat(findItem(service.list(42L), "github").getDisplayName()).isEqualTo("Alice");
        assertThat(items(service.list(42L)).stream()
                .noneMatch(item -> item.getProvider() == null || item.getProvider().isBlank()))
                .isTrue();
    }

    @Test
    void list_returns_snapshots_that_cannot_mutate_stored_state() {
        InMemoryConnectedAccountsService service = new InMemoryConnectedAccountsService();
        service.bindExternalAccount(42L, "github", "gh-42", "Alice");

        ConnectedAccountDto leaked = findItem(service.list(42L), "github");
        leaked.setConnected(false);
        leaked.setDisplayName("Tampered");
        leaked.setLinkedAt(null);

        ConnectedAccountDto stored = findItem(service.list(42L), "github");
        assertThat(stored).isNotNull();
        assertThat(stored.isConnected()).isTrue();
        assertThat(stored.getDisplayName()).isEqualTo("Alice");
        assertThat(stored.getLinkedAt()).isNotNull();
    }

    @Test
    void concurrent_conflicting_binds_have_one_winner_and_one_constraint_failure() throws Exception {
        InMemoryConnectedAccountsService service = new InMemoryConnectedAccountsService();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<BindOutcome> first = executor.submit(bindAttempt(service, ready, start, 100L));
            Future<BindOutcome> second = executor.submit(bindAttempt(service, ready, start, 200L));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<BindOutcome> outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            List<BindOutcome> winners = outcomes.stream()
                    .filter(outcome -> outcome.failure() == null)
                    .toList();
            List<BindOutcome> failures = outcomes.stream()
                    .filter(outcome -> outcome.failure() != null)
                    .toList();
            assertThat(winners).hasSize(1);
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0).failure()).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(service.findUserIdByExternalAccount("github", "same-gh"))
                    .isEqualTo(winners.get(0).userId());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Callable<BindOutcome> bindAttempt(
            InMemoryConnectedAccountsService service,
            CountDownLatch ready,
            CountDownLatch start,
            Long userId
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                service.bindExternalAccount(userId, "github", "same-gh", userId.toString());
                return new BindOutcome(userId, null);
            } catch (Throwable failure) {
                return new BindOutcome(userId, failure);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<ConnectedAccountDto> items(Map<String, Object> body) {
        return (List<ConnectedAccountDto>) body.get("items");
    }

    private ConnectedAccountDto findItem(Map<String, Object> body, String provider) {
        return items(body).stream()
                .filter(item -> Objects.equals(provider, item.getProvider()))
                .findFirst()
                .orElse(null);
    }

    private record BindOutcome(Long userId, Throwable failure) {}
}
