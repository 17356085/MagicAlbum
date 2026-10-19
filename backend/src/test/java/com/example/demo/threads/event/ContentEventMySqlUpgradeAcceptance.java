package com.example.demo.threads.event;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Explicit-only MySQL acceptance for upgrading a legal pre-content-event schema.
 * The class name intentionally does not match the normal Surefire test patterns.
 */
class ContentEventMySqlUpgradeAcceptance {
    private static final String MIGRATION_LOCATION = "classpath:db/migration/mysql";
    private static final String LATEST_MIGRATION_VERSION = "20261020.2";
    private static final String UPGRADE_URL = "CONTENT_EVENT_TEST_UPGRADE_JDBC_URL";
    private static final String UPGRADE_USERNAME = "CONTENT_EVENT_TEST_UPGRADE_JDBC_USERNAME";
    private static final String UPGRADE_PASSWORD = "CONTENT_EVENT_TEST_UPGRADE_JDBC_PASSWORD";
    private static final String EXPECTED_DATABASE = "CONTENT_EVENT_TEST_UPGRADE_EXPECTED_DATABASE";

    @Test
    void upgrades_legal_old_mysql_schema_and_validates_content_event_constraints() throws Exception {
        String url = requiredEnvironment(UPGRADE_URL);
        String username = requiredEnvironment(UPGRADE_USERNAME);
        String password = requiredEnvironment(UPGRADE_PASSWORD);
        String expectedDatabase = requiredEnvironment(EXPECTED_DATABASE);
        if (!expectedDatabase.matches("ba_content_upgrade_[0-9a-f]{10,32}")) {
            throw new AssertionError(EXPECTED_DATABASE + " must name this isolated upgrade database");
        }

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertMySqlDatabase(connection, expectedDatabase);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE()"))
                    .as("the isolated upgrade database must start empty")
                    .isZero();

            Flyway preContentEvents = flyway(url, username, password)
                    .target("20261018.1")
                    .load();
            preContentEvents.migrate();
            preContentEvents.validate();
            assertFlywayVersion(connection, "20261018.1");

            long legacyThreadId = insertLegacyData(connection);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='threads' "
                    + "AND column_name='content_version'"))
                    .as("the pre-content-event target must not contain content_version")
                    .isZero();

            Flyway contentEvents = flyway(url, username, password).target("20261018.2").load();
            contentEvents.migrate();
            long versionedThreadId = insertVersionedThread(connection, legacyThreadId);
            String eventId = UUID.randomUUID().toString();
            insertEvent(connection, eventId, versionedThreadId, 1L);

            Flyway complete = flyway(url, username, password).load();
            complete.migrate();
            complete.validate();

            assertThat(longValue(connection,
                    "SELECT content_version FROM threads WHERE id=?", legacyThreadId))
                    .as("legacy thread content_version")
                    .isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='content_event_outbox'"))
                    .isEqualTo(1);
            assertIndex(connection, "uk_content_event_outbox_event_id");
            assertIndex(connection, "uk_content_event_outbox_aggregate_version");
            assertIndex(connection, "idx_content_event_outbox_status_id");
            assertIndex(connection, "idx_content_event_outbox_delivery_claim");
            assertIndex(connection, "idx_content_event_outbox_delivery_lease");
            assertFlywayVersion(connection, LATEST_MIGRATION_VERSION);
            assertThat(count(connection, "SELECT COUNT(*) FROM content_event_outbox WHERE event_id=? "
                    + "AND attempts=0 AND available_at=occurred_at AND status='PENDING' "
                    + "AND lease_owner IS NULL AND lease_until IS NULL AND published_at IS NULL "
                    + "AND last_error IS NULL", eventId)).isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM content_search_control "
                    + "WHERE id=1 AND ready=0 AND pause_owner IS NULL AND active_index IS NULL")).isEqualTo(1);

            assertThatThrownBy(() -> insertEvent(connection, eventId, versionedThreadId, 2L))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                            .startsWith("23"));
            assertThatThrownBy(() -> insertEvent(connection, UUID.randomUUID().toString(), versionedThreadId, 1L))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                            .startsWith("23"));

            com.example.demo.storage.MediaRegistryMySqlAssertions.assertMediaRegistry(connection);
        }
    }

    private static FluentConfiguration flyway(String url, String username, String password) {
        return Flyway.configure()
                .dataSource(url, username, password)
                .locations(MIGRATION_LOCATION);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError(name + " must be set for the explicit MySQL upgrade acceptance");
        }
        return value;
    }

    private static void assertMySqlDatabase(Connection connection, String expectedDatabase)
            throws SQLException {
        assertThat(connection.getMetaData().getDatabaseProductName())
                .as("upgrade acceptance must use MySQL")
                .isEqualToIgnoringCase("MySQL");
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo(expectedDatabase);
        }
    }

    private static long insertLegacyData(Connection connection) throws SQLException {
        long userId;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (username,email,phone,password_hash) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "legacy-content-author");
            statement.setString(2, "legacy-content-author@example.test");
            statement.setString(3, "13900000101");
            statement.setString(4, "legacy-test-hash");
            statement.executeUpdate();
            userId = generatedId(statement);
        }

        long sectionId;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sections (name,slug) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "Legacy content events");
            statement.setString(2, "legacy-content-events");
            statement.executeUpdate();
            sectionId = generatedId(statement);
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO threads (section_id,author_id,title,content_md,spoiler,status) "
                        + "VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, sectionId);
            statement.setLong(2, userId);
            statement.setString(3, "Legacy title");
            statement.setString(4, "Legacy body");
            statement.setBoolean(5, false);
            statement.setString(6, "NORMAL");
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("The legacy fixture did not return a generated id");
            }
            return keys.getLong(1);
        }
    }

    private static long insertVersionedThread(Connection connection, long originalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO threads (section_id,author_id,title,content_md,spoiler,status,content_version) "
                        + "SELECT section_id,author_id,'Versioned legacy title','Versioned body',FALSE,'NORMAL',1 "
                        + "FROM threads WHERE id=?", Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, originalId);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static void insertEvent(Connection connection, String eventId, long aggregateId,
                                    long aggregateVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO content_event_outbox "
                        + "(event_id,schema_version,aggregate_type,aggregate_id,aggregate_version,event_type,"
                        + "occurred_at,request_id,status) VALUES (?,1,'THREAD',?,?, 'THREAD_UPSERTED',"
                        + "CURRENT_TIMESTAMP(6),NULL,'PENDING')")) {
            statement.setString(1, eventId);
            statement.setLong(2, aggregateId);
            statement.setLong(3, aggregateVersion);
            statement.executeUpdate();
        }
    }

    private static void assertFlywayVersion(Connection connection, String expected) throws SQLException {
        String actual;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history "
                             + "WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")) {
            assertThat(result.next()).isTrue();
            actual = result.getString(1);
        }
        assertThat(normalizeVersion(actual)).isEqualTo(normalizeVersion(expected));
    }

    private static void assertIndex(Connection connection, String indexName) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='content_event_outbox' "
                + "AND LOWER(index_name)=LOWER(?)", indexName))
                .as("content_event_outbox index " + indexName)
                .isEqualTo(1);
    }

    private static long count(Connection connection, String sql, Object... parameters) throws SQLException {
        return longValue(connection, sql, parameters);
    }

    private static long longValue(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Expected one scalar result");
                }
                return result.getLong(1);
            }
        }
    }

    private static String normalizeVersion(String version) {
        return version == null ? "" : version.replace('_', '.');
    }
}
