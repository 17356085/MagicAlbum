package com.example.demo.user.connected.service;

import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.sections.DevSectionsSeeder;
import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import com.example.demo.user.dto.ChangePasswordRequest;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.example.demo.user.service.UserService;
import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import com.example.demo.auth.service.LocalPasswordAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit-only MySQL 8 acceptance for verified account removal.
 *
 * <p>This class deliberately does not match the default Surefire includes. Run it
 * only after provisioning an isolated database and a read-only guard row:
 * {@code bluealbum_acceptance_guard(marker)}. The five required environment
 * variables are {@code BLUEALBUM_MYSQL_ACCEPTANCE_URL},
 * {@code BLUEALBUM_MYSQL_ACCEPTANCE_USERNAME},
 * {@code BLUEALBUM_MYSQL_ACCEPTANCE_PASSWORD},
 * {@code BLUEALBUM_MYSQL_ACCEPTANCE_DATABASE}, and
 * {@code BLUEALBUM_MYSQL_ACCEPTANCE_GUARD_MARKER}. The database name must match
 * {@code ba_content_upgrade_[0-9a-f]{10,32}} exactly; the URL must point to
 * 127.0.0.1 and a non-default, non-local-development port. Its query must
 * contain exactly {@code useSSL=false}, {@code serverTimezone=UTC},
 * {@code forceConnectionTimeZoneToSession=true}, and
 * {@code allowPublicKeyRetrieval=true}. The guard table and marker are read
 * before Spring's application context is allowed to obtain a connection.</p>
 *
 * <p>No cleanup is intentional: this test only creates random users and account
 * bindings in the explicitly guarded database, never clears it, and never deletes
 * pre-existing rows. The verified unlink assertions delete only the random
 * bindings created by their own fixture.</p>
 */
@EnabledIf(value = "hasAnyMySqlAcceptanceEnvironment", disabledReason =
        "MySQL acceptance is opt-in; provide the complete dedicated environment contract")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "feature.mp.read.threads=false",
        "app.rate-limit.fail-closed=false",
        "app.auth.verify.mode=off",
        "app.rabbit.enabled=false",
        "app.notifications.client.mode=mock",
        "app.notifications.client.enabled=false",
        "app.search.enabled=false",
        "app.content.events.enabled=false",
        "app.media.registration-enabled=false",
        "app.media.processing-enabled=false",
        "spring.task.scheduling.enabled=false",
        "ai.provider=mock"
})
class VerifiedDisconnectMySqlAcceptance {
    private static final String URL_ENV = "BLUEALBUM_MYSQL_ACCEPTANCE_URL";
    private static final String USERNAME_ENV = "BLUEALBUM_MYSQL_ACCEPTANCE_USERNAME";
    private static final String PASSWORD_ENV = "BLUEALBUM_MYSQL_ACCEPTANCE_PASSWORD";
    private static final String DATABASE_ENV = "BLUEALBUM_MYSQL_ACCEPTANCE_DATABASE";
    private static final String GUARD_MARKER_ENV = "BLUEALBUM_MYSQL_ACCEPTANCE_GUARD_MARKER";
    private static final String DATABASE_PREFIX = "ba_content_upgrade_";
    private static final String GUARD_TABLE = "bluealbum_acceptance_guard";
    private static final String INITIAL_PASSWORD = "InitialPassword1";
    private static final String CHANGED_PASSWORD = "ChangedPassword2";
    private static final String PROVIDER = "github";
    private static final Duration CONCURRENCY_WAIT = Duration.ofSeconds(20);

    @Autowired private UserRepository users;
    @Autowired private UserConnectedAccountRepository bindings;
    @Autowired private ConnectedAccountsService accounts;
    @Autowired private VerifiedConnectedAccountRemovalService removal;
    @Autowired private ConnectedAccountBindingTag bindingTags;
    @Autowired private PasswordEncoder passwords;
    @Autowired private LocalPasswordAuthenticator authenticator;
    @Autowired private UserService userService;
    @Autowired private PlatformTransactionManager transactionManager;

    /* These beans are replaced so application startup cannot seed or call external systems. */
    @MockitoBean private DistributedRateLimiter rateLimiter;
    @MockitoBean private DevSectionsSeeder devSectionsSeeder;
    @MockitoBean private ContentWriteGateService contentWriteGateService;

    /** JUnit disables the class before context creation when no opt-in value exists. */
    static boolean hasAnyMySqlAcceptanceEnvironment() {
        return value(URL_ENV) != null
                || value(USERNAME_ENV) != null
                || value(PASSWORD_ENV) != null
                || value(DATABASE_ENV) != null
                || value(GUARD_MARKER_ENV) != null;
    }

    /**
     * Dynamic properties run before context refresh. The preflight opens only the
     * supplied local database, verifies its selected database and guard marker,
     * and fails before Spring can obtain a pool connection or write schema data.
     */
    @DynamicPropertySource
    static void guardedMysqlProperties(DynamicPropertyRegistry registry) {
        MysqlAcceptanceConfiguration configuration = MysqlAcceptanceConfiguration.loadAndVerify();
        registry.add("spring.datasource.url", configuration::url);
        registry.add("spring.datasource.username", configuration::username);
        registry.add("spring.datasource.password", configuration::password);
    }

    @Test
    void password_change_then_verified_unlink_is_serial_and_reads_back_local_credentials() {
        User user = createUser("serial");
        String originalExternalId = randomExternalId("serial-old");
        String replacementExternalId = randomExternalId("serial-new");
        accounts.bindExternalAccount(user.getId(), PROVIDER, originalExternalId, "original");
        String originalTag = bindingTag(user.getId());

        ChangePasswordRequest change = new ChangePasswordRequest();
        change.setCurrentPassword(INITIAL_PASSWORD);
        change.setNewPassword(CHANGED_PASSWORD);
        userService.changePassword(user.getId(), change);

        User persisted = users.findById(user.getId()).orElseThrow();
        assertThat(passwords.matches(INITIAL_PASSWORD, persisted.getPasswordHash())).isFalse();
        assertThat(passwords.matches(CHANGED_PASSWORD, persisted.getPasswordHash())).isTrue();
        assertThat(authenticator.authenticate(user.getEmail(), null, CHANGED_PASSWORD).getId())
                .isEqualTo(user.getId());

        removal.disconnect(user.getId(), PROVIDER.toUpperCase(Locale.ROOT),
                "  " + user.getEmail().toUpperCase(Locale.ROOT) + "  ", CHANGED_PASSWORD, originalTag);
        assertThat(accounts.findUserIdByExternalAccount(PROVIDER, originalExternalId)).isNull();

        accounts.bindExternalAccount(user.getId(), PROVIDER, replacementExternalId, "replacement");
        String replacementTag = bindingTag(user.getId());
        assertThat(replacementTag).isNotEqualTo(originalTag);

        assertConflict(() -> removal.disconnect(user.getId(), PROVIDER, user.getEmail(),
                CHANGED_PASSWORD, originalTag));
        assertThat(accounts.findUserIdByExternalAccount(PROVIDER, originalExternalId)).isNull();
        assertThat(accounts.findUserIdByExternalAccount(PROVIDER, replacementExternalId))
                .isEqualTo(user.getId());
        var replacement = bindings.findByUserIdAndProvider(user.getId(), PROVIDER).orElseThrow();
        assertThat(replacement.getExternalId()).isEqualTo(replacementExternalId);
    }

    @Test
    void a_real_users_row_lock_blocks_verified_unlink_until_the_transaction_releases() throws Exception {
        User user = createUser("lock");
        String externalId = randomExternalId("lock-original");
        accounts.bindExternalAccount(user.getId(), PROVIDER, externalId, "original");
        String bindingTag = bindingTag(user.getId());

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch unlinkStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> lockHolder = executor.submit(() -> holdUserRowLock(
                user.getId(), lockAcquired, releaseLock));
        try {
            awaitLatch(lockAcquired, "users row lock");
            Future<?> unlink = executor.submit(() -> {
                unlinkStarted.countDown();
                removal.disconnect(user.getId(), PROVIDER, user.getEmail(), INITIAL_PASSWORD, bindingTag);
                return null;
            });
            awaitLatch(unlinkStarted, "verified unlink worker");
            assertNotCompletedWhileLocked(unlink, "verified unlink");

            releaseLock.countDown();
            assertThat(completedFailure(unlink, "verified unlink after row-lock release"))
                    .as("verified unlink must complete after the real lock is released")
                    .isNull();
            assertThat(completedFailure(lockHolder, "users row-lock holder"))
                    .as("the real lock transaction must commit cleanly")
                    .isNull();
            assertThat(accounts.findUserIdByExternalAccount(PROVIDER, externalId)).isNull();
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
            if (!executor.awaitTermination(CONCURRENCY_WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("row-lock acceptance workers did not terminate");
            }
        }
    }

    @Test
    void verified_unlink_and_same_provider_rebind_compete_under_real_transactions() throws Exception {
        User user = createUser("race");
        String originalExternalId = randomExternalId("race-old");
        String replacementExternalId = randomExternalId("race-new");
        accounts.bindExternalAccount(user.getId(), PROVIDER, originalExternalId, "original");
        String originalTag = bindingTag(user.getId());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> unlink = executor.submit(() -> {
            awaitStart(ready, start, "verified unlink");
            removal.disconnect(user.getId(), PROVIDER, user.getEmail(), INITIAL_PASSWORD, originalTag);
            return null;
        });
        Future<?> rebind = executor.submit(() -> {
            awaitStart(ready, start, "same-provider rebind");
            accounts.bindExternalAccount(user.getId(), PROVIDER, replacementExternalId, "replacement");
            return null;
        });

        Throwable unlinkFailure = null;
        Throwable rebindFailure = null;
        try {
            if (!ready.await(CONCURRENCY_WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent acceptance workers did not become ready");
            }
            start.countDown();
            unlinkFailure = completedFailure(unlink, "verified unlink");
            rebindFailure = completedFailure(rebind, "same-provider rebind");
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(CONCURRENCY_WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent acceptance workers did not terminate");
            }
        }

        assertThat(unlinkFailure).as("verified unlink must commit without hiding a failure").isNull();
        if (rebindFailure != null) {
            assertThat(rebindFailure).as("a bind that wins the user lock may only report the expected conflict")
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) rebindFailure).getStatusCode().value()).isEqualTo(409);
        }

        assertThat(accounts.findUserIdByExternalAccount(PROVIDER, originalExternalId)).isNull();
        if (rebindFailure != null) {
            assertThat(accounts.findUserIdByExternalAccount(PROVIDER, replacementExternalId)).isNull();
            accounts.bindExternalAccount(user.getId(), PROVIDER, replacementExternalId, "replacement-after-race");
        }

        assertThat(accounts.findUserIdByExternalAccount(PROVIDER, originalExternalId)).isNull();
        assertThat(accounts.findUserIdByExternalAccount(PROVIDER, replacementExternalId))
                .isEqualTo(user.getId());
        assertConflict(() -> removal.disconnect(user.getId(), PROVIDER, user.getEmail(),
                INITIAL_PASSWORD, originalTag));
        var replacement = bindings.findByUserIdAndProvider(user.getId(), PROVIDER).orElseThrow();
        assertThat(replacement.getExternalId()).isEqualTo(replacementExternalId);
    }

    private User createUser(String scenario) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User user = new User();
        user.setUsername("mysql_accept_" + scenario + "_" + suffix);
        user.setEmail("mysql_accept_" + scenario + "_" + suffix + "@example.test");
        user.setPasswordHash(passwords.encode(INITIAL_PASSWORD));
        return users.saveAndFlush(user);
    }

    private String bindingTag(Long userId) {
        return bindings.findByUserIdAndProvider(userId, PROVIDER)
                .map(bindingTags::forBinding)
                .orElseThrow(() -> new AssertionError("expected a persisted binding"));
    }

    private static String randomExternalId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        org.assertj.core.api.Assertions.assertThatThrownBy(action)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
    }

    private static void awaitStart(CountDownLatch ready, CountDownLatch start, String worker) {
        ready.countDown();
        try {
            if (!start.await(CONCURRENCY_WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError(worker + " did not receive the start signal");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(worker + " was interrupted before the start signal", ex);
        }
    }

    private void holdUserRowLock(Long userId, CountDownLatch lockAcquired, CountDownLatch releaseLock) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            users.findByIdForAccountUpdate(userId)
                    .orElseThrow(() -> new AssertionError("row-lock fixture user disappeared"));
            lockAcquired.countDown();
            try {
                if (!releaseLock.await(CONCURRENCY_WAIT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new AssertionError("row-lock holder was not released by the test");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("row-lock holder was interrupted", ex);
            }
        });
    }

    private static void awaitLatch(CountDownLatch latch, String name) throws InterruptedException {
        if (!latch.await(CONCURRENCY_WAIT.toSeconds(), TimeUnit.SECONDS)) {
            throw new AssertionError(name + " did not become ready");
        }
    }

    private static void assertNotCompletedWhileLocked(Future<?> future, String worker) {
        try {
            future.get(1, TimeUnit.SECONDS);
            throw new AssertionError(worker + " completed while the target users row lock was held");
        } catch (TimeoutException expected) {
            // The real transaction still holds the users row lock; completion is checked after release.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(worker + " lock probe was interrupted", ex);
        } catch (ExecutionException ex) {
            throw new AssertionError(worker + " failed before the real users row lock was released", rootCause(ex));
        }
    }

    private static Throwable completedFailure(Future<?> future, String worker) {
        try {
            future.get(CONCURRENCY_WAIT.toSeconds(), TimeUnit.SECONDS);
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(worker + " wait was interrupted", ex);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new AssertionError(worker + " timed out; a timeout is not a successful acceptance result", ex);
        } catch (ExecutionException ex) {
            return rootCause(ex);
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current instanceof ExecutionException) {
            current = current.getCause();
        }
        return current;
    }

    private static String value(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private record MysqlAcceptanceConfiguration(String url, String username, String password,
                                                String database, String guardMarker) {
        private static MysqlAcceptanceConfiguration loadAndVerify() {
            String url = required(URL_ENV);
            String username = required(USERNAME_ENV);
            String password = required(PASSWORD_ENV);
            String database = required(DATABASE_ENV);
            String guardMarker = required(GUARD_MARKER_ENV);
            validateMarker(guardMarker);
            parseAndValidateUrl(url, database);
            MysqlAcceptanceConfiguration configuration =
                    new MysqlAcceptanceConfiguration(url, username, password, database, guardMarker);
            verifyDatabaseAndGuard(configuration);
            return configuration;
        }

        private static String required(String name) {
            String value = VerifiedDisconnectMySqlAcceptance.value(name);
            if (value == null) {
                throw new IllegalStateException("Missing required MySQL acceptance environment variable: " + name);
            }
            return value;
        }

        private static URI parseAndValidateUrl(String url, String expectedDatabase) {
            if (!url.startsWith("jdbc:mysql://")) {
                throw new IllegalStateException("MySQL acceptance URL must use jdbc:mysql://");
            }
            URI uri;
            try {
                uri = new URI(url.substring("jdbc:".length()));
            } catch (URISyntaxException ex) {
                throw new IllegalStateException("MySQL acceptance URL is not a valid URI", ex);
            }
            if (!"mysql".equalsIgnoreCase(uri.getScheme())
                    || !"127.0.0.1".equals(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalStateException("MySQL acceptance URL must target 127.0.0.1 without URL credentials");
            }
            int port = uri.getPort();
            if (port <= 0 || port > 65535 || port == 3306 || port == 3307 || port == 35174) {
                throw new IllegalStateException("MySQL acceptance URL must use a dedicated non-default local port");
            }
            String rawPath = uri.getRawPath();
            if (rawPath == null || !rawPath.matches("/[A-Za-z0-9_]+")
                    || !expectedDatabase.equals(rawPath.substring(1))) {
                throw new IllegalStateException("MySQL acceptance URL database does not match the expected database");
            }
            if (!expectedDatabase.matches("^" + DATABASE_PREFIX + "[0-9a-f]{10,32}$")) {
                throw new IllegalStateException("MySQL acceptance database must use the dedicated prefix");
            }
            validateQuery(uri.getRawQuery());
            return uri;
        }

        private static void validateQuery(String rawQuery) {
            Map<String, String> required = Map.of(
                    "useSSL", "false",
                    "serverTimezone", "UTC",
                    "forceConnectionTimeZoneToSession", "true",
                    "allowPublicKeyRetrieval", "true");
            if (rawQuery == null || rawQuery.isBlank() || rawQuery.indexOf('%') >= 0
                    || rawQuery.indexOf('+') >= 0) {
                throw new IllegalStateException("MySQL acceptance URL query must use literal approved parameters");
            }
            String[] pairs = rawQuery.split("&", -1);
            Set<String> seen = new HashSet<>();
            if (pairs.length != required.size()) {
                throw new IllegalStateException("MySQL acceptance URL query contains an unapproved parameter");
            }
            for (String pair : pairs) {
                int equals = pair.indexOf('=');
                if (equals <= 0 || equals != pair.lastIndexOf('=')) {
                    throw new IllegalStateException("MySQL acceptance URL query must use key=value pairs");
                }
                String key = pair.substring(0, equals);
                String value = pair.substring(equals + 1);
                if (!required.containsKey(key) || !required.get(key).equals(value) || !seen.add(key)) {
                    throw new IllegalStateException("MySQL acceptance URL query contains an unapproved parameter");
                }
            }
            if (!seen.equals(required.keySet())) {
                throw new IllegalStateException("MySQL acceptance URL query is missing an approved parameter");
            }
        }

        private static void validateMarker(String marker) {
            if (!marker.matches("^[A-Za-z0-9][A-Za-z0-9._-]{7,127}$")) {
                throw new IllegalStateException("MySQL acceptance guard marker has an invalid shape");
            }
        }

        private static void verifyDatabaseAndGuard(MysqlAcceptanceConfiguration configuration) {
            try (Connection connection = DriverManager.getConnection(
                    configuration.url(), configuration.username(), configuration.password())) {
                DatabaseMetaData metadata = connection.getMetaData();
                if (!"MySQL".equals(metadata.getDatabaseProductName())
                        || metadata.getDatabaseMajorVersion() != 8) {
                    throw new IllegalStateException("MySQL acceptance requires MySQL 8 metadata");
                }
                connection.setReadOnly(true);
                try (PreparedStatement databaseQuery = connection.prepareStatement("SELECT DATABASE()")) {
                    try (ResultSet result = databaseQuery.executeQuery()) {
                        if (!result.next() || !configuration.database().equals(result.getString(1))) {
                            throw new IllegalStateException("MySQL acceptance connection selected an unexpected database");
                        }
                    }
                }
                try (PreparedStatement guardQuery = connection.prepareStatement(
                        "SELECT marker FROM " + GUARD_TABLE + " WHERE marker = ?")) {
                    guardQuery.setString(1, configuration.guardMarker());
                    try (ResultSet result = guardQuery.executeQuery()) {
                        int matches = 0;
                        while (result.next()) {
                            matches++;
                            if (!configuration.guardMarker().equals(result.getString(1))) {
                                throw new IllegalStateException("MySQL acceptance guard returned a different marker");
                            }
                        }
                        if (matches != 1) {
                            throw new IllegalStateException("MySQL acceptance guard marker was not uniquely pre-provisioned");
                        }
                    }
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("MySQL acceptance preflight could not read the selected guard database", ex);
            }
        }
    }
}
