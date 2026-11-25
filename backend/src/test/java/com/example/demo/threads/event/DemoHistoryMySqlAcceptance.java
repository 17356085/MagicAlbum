package com.example.demo.threads.event;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit-only MySQL acceptance for a real historical dump and restore.
 * The class name intentionally does not match the normal Surefire test patterns.
 */
class DemoHistoryMySqlAcceptance {
    private static final String MIGRATION_LOCATION = "classpath:db/migration/mysql";
    private static final String BASELINE_VERSION = "20261124.3";
    private static final String JDBC_URL_ENV = "DEMO_HISTORY_JDBC_URL";
    private static final String DATABASE_ENV = "DEMO_HISTORY_DB";
    private static final String USERNAME_ENV = "DEMO_HISTORY_JDBC_USER";
    private static final String PASSWORD_ENV = "DEMO_HISTORY_JDBC_PASSWORD";
    private static final Pattern JDBC_URL = Pattern.compile(
            "^jdbc:mysql://(127\\.0\\.0\\.1):(\\d{1,5})/([a-z0-9_]+)(?:\\?.*)?$");
    private static final String DATABASE_PATTERN = "ba_demo_history_(legacy|candidate|rollback)_[0-9a-f]{10,32}";

    private static final String USERNAME = "demo_history_persisted_user";
    private static final String EMAIL = "demo-history-persisted-user@example.test";
    private static final String PHONE = "19900000123";
    private static final String SECTION_NAME = "Demo history section";
    private static final String SECTION_SLUG = "demo-history-seeded-section";
    private static final String THREAD_TITLE = "Demo history persisted thread";
    private static final String THREAD_BODY = "Demo history persisted body";
    private static final String MEDIA_ASSET_ID = "00000000-0000-4000-8000-000000000001";
    private static final String MEDIA_JOB_ID = "00000000-0000-4000-8000-000000000002";
    private static final String MEDIA_EVENT_ID = "00000000-0000-4000-8000-000000000003";
    private static final String MEDIA_OBJECT_KEY = "demo-history/persisted-image.png";
    private static final String MEDIA_SHA256 = "0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef";
    private static final String CONTENT_EVENT_ID = "00000000-0000-4000-8000-000000000004";
    private static final String CONTENT_REQUEST_ID = "demo-history-content-request";
    private static final String CANDIDATE_MARKER = "demo-history-candidate-marker";
    private static final Date CANDIDATE_MARKER_DAY = Date.valueOf("2099-12-31");

    @Test
    void seedLegacyBackup() throws Exception {
        Environment environment = environment("legacy");
        try (Connection connection = connect(environment)) {
            assertConnection(connection, environment);
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_type='BASE TABLE'"))
                    .as("the legacy demo-history database must start empty")
                    .isZero();

            Flyway baseline = flyway(environment).target(BASELINE_VERSION).load();
            baseline.migrate();
            baseline.validate();
            assertFlywayVersion(connection, BASELINE_VERSION);

            long userId = insertUser(connection);
            long sectionId = insertSection(connection);
            long threadId = insertThread(connection, userId, sectionId);
            insertMediaFixture(connection, userId);
            long contentEventOutboxId = insertContentEventFixture(connection, threadId);
            insertKafkaDeliveryFixture(connection, contentEventOutboxId);

            SeededRows seededRows = readSeededRows(connection);
            assertThat(seededRows.userId()).isEqualTo(userId);
            assertThat(seededRows.threadId()).isEqualTo(threadId);
            assertThat(seededRows.contentEventOutboxId()).isEqualTo(contentEventOutboxId);
            assertSeededRows(connection, seededRows, false);
            assertCandidateColumnsAbsent(connection);
            assertThat(count(connection, "SELECT COUNT(*) FROM popular_search_terms WHERE keyword=?",
                    CANDIDATE_MARKER)).isZero();
        }
    }

    @Test
    void upgradeRestoredBackup() throws Exception {
        Environment environment = environment("candidate");
        try (Connection connection = connect(environment)) {
            assertConnection(connection, environment);
            assertFlywayVersion(connection, BASELINE_VERSION);
            flyway(environment).target(BASELINE_VERSION).load().validate();

            SeededRows seededRows = readSeededRows(connection);
            assertSeededRows(connection, seededRows, false);
            assertCandidateColumnsAbsent(connection);

            Flyway complete = flyway(environment).load();
            complete.migrate();
            complete.validate();
            assertThat(complete.info().pending())
                    .as("the restored candidate must have no pending migrations")
                    .isEmpty();
            assertFlywayVersion(connection, complete.info().current().getVersion().getVersion());

            assertSeededRows(connection, seededRows, true);
            insertCandidateMarker(connection, seededRows.userId());
            assertThat(count(connection, "SELECT COUNT(*) FROM popular_search_terms "
                    + "WHERE account_id=? AND keyword=? AND normalized_keyword=? AND search_day=?",
                    seededRows.userId(), CANDIDATE_MARKER, CANDIDATE_MARKER, CANDIDATE_MARKER_DAY))
                    .as("the candidate-only marker must be writable")
                    .isEqualTo(1);
            assertCandidateColumns(connection);
        }
    }

    @Test
    void verifyRollbackBackup() throws Exception {
        Environment environment = environment("rollback");
        try (Connection connection = connect(environment)) {
            assertConnection(connection, environment);
            assertFlywayVersion(connection, BASELINE_VERSION);
            flyway(environment).target(BASELINE_VERSION).load().validate();

            SeededRows seededRows = readSeededRows(connection);
            assertSeededRows(connection, seededRows, false);
            assertCandidateColumnsAbsent(connection);
            assertThat(count(connection, "SELECT COUNT(*) FROM popular_search_terms "
                    + "WHERE account_id=? AND keyword=? AND normalized_keyword=? AND search_day=?",
                    seededRows.userId(), CANDIDATE_MARKER, CANDIDATE_MARKER, CANDIDATE_MARKER_DAY))
                    .as("the rollback restore must not contain the candidate-only marker")
                    .isZero();
        }
    }

    private static Environment environment(String stage) {
        String url = requiredEnvironment(JDBC_URL_ENV);
        String database = requiredEnvironment(DATABASE_ENV);
        String username = requiredEnvironment(USERNAME_ENV);
        String password = requiredEnvironment(PASSWORD_ENV);
        if (!database.matches(DATABASE_PATTERN) || !database.startsWith("ba_demo_history_" + stage + "_")) {
            throw new AssertionError(DATABASE_ENV + " must name an isolated demo-history database");
        }

        Matcher matcher = JDBC_URL.matcher(url);
        if (!matcher.matches()) {
            throw new AssertionError(JDBC_URL_ENV + " must use jdbc:mysql://127.0.0.1:<port>/<database>");
        }
        int port = Integer.parseInt(matcher.group(2));
        if (port < 1024 || port > 65535) {
            throw new AssertionError(JDBC_URL_ENV + " must use an unprivileged loopback port");
        }
        if (!database.equals(matcher.group(3))) {
            throw new AssertionError(JDBC_URL_ENV + " and " + DATABASE_ENV + " must name the same database");
        }
        return new Environment(url, database, username, password, port);
    }

    private static Connection connect(Environment environment) throws SQLException {
        return DriverManager.getConnection(environment.url(), environment.username(), environment.password());
    }

    private static FluentConfiguration flyway(Environment environment) {
        return Flyway.configure()
                .dataSource(environment.url(), environment.username(), environment.password())
                .locations(MIGRATION_LOCATION);
    }

    private static void assertConnection(Connection connection, Environment environment) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        assertThat(metadata.getDatabaseProductName())
                .as("demo-history acceptance must use MySQL")
                .isEqualToIgnoringCase("MySQL");
        Matcher metadataUrl = JDBC_URL.matcher(metadata.getURL());
        assertThat(metadataUrl.matches())
                .as("the JDBC metadata URL must remain on the loopback harness endpoint")
                .isTrue();
        assertThat(metadataUrl.group(1)).isEqualTo("127.0.0.1");
        assertThat(Integer.parseInt(metadataUrl.group(2))).isEqualTo(environment.port());
        assertThat(metadataUrl.group(3)).isEqualTo(environment.database());
        assertThat(stringValue(connection, "SELECT SUBSTRING_INDEX(CURRENT_USER(),'@',1)"))
                .as("the active database user must match the harness user")
                .isEqualTo(environment.username());
        assertThat(stringValue(connection, "SELECT DATABASE()"))
                .as("the active database must match the harness database")
                .isEqualTo(environment.database());
    }

    private static long insertUser(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (username,email,phone,password_hash) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, USERNAME);
            statement.setString(2, EMAIL);
            statement.setString(3, PHONE);
            statement.setString(4, "demo-history-test-hash");
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static long insertSection(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sections (name,slug) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, SECTION_NAME);
            statement.setString(2, SECTION_SLUG);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static long insertThread(Connection connection, long userId, long sectionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO threads (section_id,author_id,title,content_md,spoiler,status) "
                        + "VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, sectionId);
            statement.setLong(2, userId);
            statement.setString(3, THREAD_TITLE);
            statement.setString(4, THREAD_BODY);
            statement.setBoolean(5, false);
            statement.setString(6, "NORMAL");
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static void insertMediaFixture(Connection connection, long ownerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO media_asset (asset_id,owner_id,provider,object_key,url,content_type,byte_size,sha256,"
                        + "status,processing_status,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))")) {
            statement.setString(1, MEDIA_ASSET_ID);
            statement.setLong(2, ownerId);
            statement.setString(3, "local");
            statement.setString(4, MEDIA_OBJECT_KEY);
            statement.setString(5, "https://example.test/media/demo-history.png");
            statement.setString(6, "image/png");
            statement.setLong(7, 128L);
            statement.setString(8, MEDIA_SHA256);
            statement.setString(9, "READY");
            statement.setString(10, "NOT_REQUESTED");
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO media_processing_job "
                        + "(job_id,asset_id,job_version,preset,processor_version,status,attempts,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))")) {
            statement.setString(1, MEDIA_JOB_ID);
            statement.setString(2, MEDIA_ASSET_ID);
            statement.setInt(3, 1);
            statement.setString(4, "thumbnail");
            statement.setString(5, "demo-history-processor");
            statement.setString(6, "PENDING");
            statement.setInt(7, 0);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO media_request_outbox "
                        + "(event_id,asset_id,job_id,job_version,preset,processor_version,occurred_at,status,"
                        + "attempts,available_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?,?,?)")) {
            statement.setString(1, MEDIA_EVENT_ID);
            statement.setString(2, MEDIA_ASSET_ID);
            statement.setString(3, MEDIA_JOB_ID);
            statement.setInt(4, 1);
            statement.setString(5, "thumbnail");
            statement.setString(6, "demo-history-processor");
            statement.setString(7, "PUBLISHED");
            statement.setInt(8, 2);
            statement.setString(9, "2026-01-02 03:04:05.123456");
            statement.executeUpdate();
        }
    }

    private static long insertContentEventFixture(Connection connection, long threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO content_event_outbox "
                        + "(event_id,schema_version,aggregate_type,aggregate_id,aggregate_version,event_type,"
                        + "occurred_at,request_id,status,attempts,available_at) "
                        + "VALUES (?,1,'THREAD',?,1,'THREAD_UPSERTED',CURRENT_TIMESTAMP(6),?,'PENDING',0,"
                        + "CURRENT_TIMESTAMP(6))", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, CONTENT_EVENT_ID);
            statement.setLong(2, threadId);
            statement.setString(3, CONTENT_REQUEST_ID);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static void insertKafkaDeliveryFixture(Connection connection, long contentEventOutboxId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO content_event_kafka_delivery (outbox_id,available_at) "
                        + "VALUES (?,CURRENT_TIMESTAMP(6))")) {
            statement.setLong(1, contentEventOutboxId);
            statement.executeUpdate();
        }
    }

    private static void insertCandidateMarker(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO popular_search_terms "
                        + "(search_type,account_id,keyword,normalized_keyword,search_day) VALUES ('threads',?,?,?,?)")) {
            statement.setLong(1, accountId);
            statement.setString(2, CANDIDATE_MARKER);
            statement.setString(3, CANDIDATE_MARKER);
            statement.setDate(4, CANDIDATE_MARKER_DAY);
            statement.executeUpdate();
        }
    }

    private static SeededRows readSeededRows(Connection connection) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM users WHERE username=? AND email=? AND phone=?",
                USERNAME, EMAIL, PHONE)).isEqualTo(1);
        long userId = longValue(connection, "SELECT id FROM users WHERE username=?", USERNAME);
        assertThat(count(connection, "SELECT COUNT(*) FROM sections WHERE slug=? AND name=?",
                SECTION_SLUG, SECTION_NAME)).isEqualTo(1);
        long sectionId = longValue(connection, "SELECT id FROM sections WHERE slug=?", SECTION_SLUG);
        assertThat(count(connection, "SELECT COUNT(*) FROM threads WHERE section_id=? AND author_id=? "
                + "AND title=? AND content_md=? AND status='NORMAL'", sectionId, userId, THREAD_TITLE, THREAD_BODY))
                .isEqualTo(1);
        long threadId = longValue(connection, "SELECT id FROM threads WHERE section_id=? AND author_id=? AND title=?",
                sectionId, userId, THREAD_TITLE);
        long contentEventOutboxId = longValue(connection,
                "SELECT id FROM content_event_outbox WHERE event_id=?", CONTENT_EVENT_ID);
        return new SeededRows(userId, threadId, contentEventOutboxId);
    }

    private static void assertSeededRows(Connection connection, SeededRows seededRows, boolean latest)
            throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM media_asset WHERE asset_id=? AND owner_id=? "
                + "AND object_key=? AND status='READY' AND processing_status='NOT_REQUESTED'",
                MEDIA_ASSET_ID, seededRows.userId(), MEDIA_OBJECT_KEY)).isEqualTo(1);
        assertThat(count(connection, "SELECT COUNT(*) FROM media_processing_job WHERE job_id=? AND asset_id=? "
                + "AND job_version=1 AND preset='thumbnail' AND status='PENDING' AND attempts=0",
                MEDIA_JOB_ID, MEDIA_ASSET_ID)).isEqualTo(1);
        String mediaOutboxSql = "SELECT COUNT(*) FROM media_request_outbox WHERE event_id=? AND asset_id=? "
                + "AND job_id=? AND job_version=1 AND status='PUBLISHED' AND attempts=2";
        if (latest) {
            mediaOutboxSql += " AND trace_parent IS NULL AND trace_state IS NULL";
        }
        assertThat(count(connection, mediaOutboxSql, MEDIA_EVENT_ID, MEDIA_ASSET_ID, MEDIA_JOB_ID)).isEqualTo(1);

        assertThat(count(connection, "SELECT COUNT(*) FROM content_event_outbox WHERE id=? AND event_id=? "
                + "AND aggregate_type='THREAD' AND aggregate_id=? AND aggregate_version=1 "
                + "AND event_type='THREAD_UPSERTED' AND status='PENDING'",
                seededRows.contentEventOutboxId(), CONTENT_EVENT_ID, seededRows.threadId())).isEqualTo(1);
        assertThat(count(connection, "SELECT COUNT(*) FROM content_event_kafka_delivery WHERE outbox_id=? "
                + "AND published_at IS NULL AND last_error IS NULL", seededRows.contentEventOutboxId()))
                .isEqualTo(1);
        if (latest) {
            assertThat(count(connection, "SELECT COUNT(*) FROM content_event_kafka_delivery WHERE outbox_id=? "
                    + "AND attempt_count=0 AND failed_at IS NULL", seededRows.contentEventOutboxId()))
                    .as("the Kafka retry migration must preserve the seeded delivery row")
                    .isEqualTo(1);
        }
    }

    private static void assertCandidateColumnsAbsent(Connection connection) throws SQLException {
        assertColumnAbsent(connection, "media_request_outbox", "trace_parent");
        assertColumnAbsent(connection, "media_request_outbox", "trace_state");
        assertColumnAbsent(connection, "content_event_kafka_delivery", "attempt_count");
        assertColumnAbsent(connection, "content_event_kafka_delivery", "failed_at");
        assertIndexAbsent(connection, "content_event_kafka_delivery", "idx_kafka_delivery_claim_state");
        assertIndexAbsent(connection, "content_event_kafka_delivery", "idx_kafka_delivery_failed");
    }

    private static void assertCandidateColumns(Connection connection) throws SQLException {
        assertColumn(connection, "media_request_outbox", "trace_parent", "varchar", true);
        assertColumn(connection, "media_request_outbox", "trace_state", "varchar", true);
        assertColumn(connection, "content_event_kafka_delivery", "attempt_count", "int", false);
        assertColumn(connection, "content_event_kafka_delivery", "failed_at", "datetime", true);
        assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='content_event_kafka_delivery' "
                + "AND column_name='attempt_count' AND CAST(column_default AS CHAR)='0'"))
                .as("Kafka delivery retry attempts must default to zero")
                .isEqualTo(1);
        assertIndex(connection, "content_event_kafka_delivery", "idx_kafka_delivery_claim_state",
                "published_at,failed_at,available_at,outbox_id");
        assertIndex(connection, "content_event_kafka_delivery", "idx_kafka_delivery_failed",
                "failed_at,outbox_id");
    }

    private static void assertFlywayVersion(Connection connection, String expected) throws SQLException {
        String actual = stringValue(connection,
                "SELECT version FROM flyway_schema_history WHERE success=1 "
                        + "AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
        assertThat(normalizeVersion(actual)).isEqualTo(normalizeVersion(expected));
    }

    private static void assertColumn(Connection connection, String tableName, String columnName,
                                     String dataType, boolean nullable) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=? "
                + "AND data_type=? AND is_nullable=?", tableName, columnName, dataType, nullable ? "YES" : "NO"))
                .as(tableName + "." + columnName + " has the expected type and nullability")
                .isEqualTo(1);
    }

    private static void assertColumnAbsent(Connection connection, String tableName, String columnName)
            throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", tableName, columnName))
                .as(tableName + "." + columnName + " is absent before the candidate migration")
                .isZero();
    }

    private static void assertIndex(Connection connection, String tableName, String indexName,
                                    String expectedColumns) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name=? AND LOWER(index_name)=LOWER(?)",
                tableName, indexName)).isEqualTo(1);
        assertThat(stringValue(connection, "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index "
                + "SEPARATOR ',') FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name=? AND LOWER(index_name)=LOWER(?)", tableName, indexName))
                .as(tableName + " index " + indexName + " columns")
                .isEqualTo(expectedColumns);
    }

    private static void assertIndexAbsent(Connection connection, String tableName, String indexName)
            throws SQLException {
        assertThat(count(connection, "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name=? AND LOWER(index_name)=LOWER(?)",
                tableName, indexName))
                .as(tableName + " index " + indexName + " is absent before the candidate migration")
                .isZero();
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("The demo-history fixture did not return a generated id");
            }
            return keys.getLong(1);
        }
    }

    private static long count(Connection connection, String sql, Object... parameters) throws SQLException {
        return longValue(connection, sql, parameters);
    }

    private static long longValue(Connection connection, String sql, Object... parameters) throws SQLException {
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

    private static String stringValue(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Expected one scalar result");
                }
                return result.getString(1);
            }
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError(name + " must be set for the explicit demo-history acceptance");
        }
        return value;
    }

    private static String normalizeVersion(String version) {
        return version == null ? "" : version.replace('_', '.');
    }

    private record Environment(String url, String database, String username, String password, int port) {}

    private record SeededRows(long userId, long threadId, long contentEventOutboxId) {}
}
