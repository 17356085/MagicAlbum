package com.example.demo.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared assertions invoked only by the explicit, isolated MySQL upgrade acceptance. */
public final class MediaRegistryMySqlAssertions {
    private MediaRegistryMySqlAssertions() {}

    public static void assertMediaRegistry(Connection connection) throws SQLException {
        assertTable(connection, "media_asset");
        assertTable(connection, "media_processing_job");
        assertTable(connection, "media_request_outbox");

        assertColumn(connection, "media_asset", "asset_id", "varchar");
        assertColumn(connection, "media_asset", "owner_id", "bigint");
        assertColumn(connection, "media_asset", "provider", "varchar");
        assertColumn(connection, "media_asset", "object_key", "varchar");
        assertColumn(connection, "media_asset", "url", "varchar");
        assertColumn(connection, "media_asset", "content_type", "varchar");
        assertColumn(connection, "media_asset", "byte_size", "bigint");
        assertColumn(connection, "media_asset", "sha256", "varchar");
        assertColumn(connection, "media_asset", "status", "varchar");
        assertColumn(connection, "media_asset", "processing_status", "varchar");
        assertColumn(connection, "media_asset", "created_at", "timestamp");
        assertColumn(connection, "media_asset", "updated_at", "timestamp");
        assertThat(stringValue(connection, "SELECT character_set_name FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='media_asset' AND column_name='sha256'"))
                .as("media_asset.sha256 character set")
                .isEqualTo("ascii");
        assertThat(stringValue(connection, "SELECT collation_name FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='media_asset' AND column_name='sha256'"))
                .as("media_asset.sha256 collation")
                .isEqualTo("ascii_bin");

        assertColumn(connection, "media_processing_job", "job_id", "varchar");
        assertColumn(connection, "media_processing_job", "asset_id", "varchar");
        assertColumn(connection, "media_processing_job", "job_version", "int");
        assertColumn(connection, "media_processing_job", "preset", "varchar");
        assertColumn(connection, "media_processing_job", "processor_version", "varchar");
        assertColumn(connection, "media_processing_job", "status", "varchar");
        assertColumn(connection, "media_processing_job", "attempts", "int");
        assertColumn(connection, "media_processing_job", "created_at", "timestamp");
        assertColumn(connection, "media_processing_job", "updated_at", "timestamp");
        assertColumn(connection, "media_processing_job", "lease_token_hash", "varchar", true);
        assertColumn(connection, "media_processing_job", "lease_until", "timestamp", true);
        assertColumn(connection, "media_processing_job", "candidate_key", "varchar", true);
        assertColumn(connection, "media_processing_job", "candidate_url", "varchar", true);
        assertColumn(connection, "media_processing_job", "candidate_sha256", "varchar", true);
        assertColumn(connection, "media_processing_job", "candidate_type", "varchar", true);
        assertColumn(connection, "media_processing_job", "candidate_size", "bigint", true);
        assertColumn(connection, "media_processing_job", "candidate_width", "int", true);
        assertColumn(connection, "media_processing_job", "candidate_height", "int", true);
        assertColumn(connection, "media_processing_job", "output_key", "varchar", true);
        assertColumn(connection, "media_processing_job", "output_url", "varchar", true);
        assertColumn(connection, "media_processing_job", "output_sha256", "varchar", true);
        assertColumn(connection, "media_processing_job", "output_type", "varchar", true);
        assertColumn(connection, "media_processing_job", "output_size", "bigint", true);
        assertColumn(connection, "media_processing_job", "output_width", "int", true);
        assertColumn(connection, "media_processing_job", "output_height", "int", true);
        assertColumn(connection, "media_processing_job", "error_code", "varchar", true);

        assertColumn(connection, "media_request_outbox", "event_id", "varchar");
        assertColumn(connection, "media_request_outbox", "asset_id", "varchar");
        assertColumn(connection, "media_request_outbox", "job_id", "varchar");
        assertColumn(connection, "media_request_outbox", "job_version", "int");
        assertColumn(connection, "media_request_outbox", "preset", "varchar");
        assertColumn(connection, "media_request_outbox", "processor_version", "varchar");
        assertColumn(connection, "media_request_outbox", "occurred_at", "timestamp");
        assertColumn(connection, "media_request_outbox", "status", "varchar");
        assertColumn(connection, "media_request_outbox", "attempts", "int");
        assertColumn(connection, "media_request_outbox", "available_at", "timestamp");
        assertColumn(connection, "media_request_outbox", "lease_owner", "varchar", true);
        assertColumn(connection, "media_request_outbox", "lease_until", "timestamp", true);
        assertColumn(connection, "media_request_outbox", "last_error", "varchar", true);

        assertIndex(connection, "media_asset", "PRIMARY", true, "asset_id");
        assertIndex(connection, "media_asset", "uk_media_asset_provider_object_key", true,
                "provider,object_key");
        assertIndex(connection, "media_asset", "idx_media_asset_owner_status", false,
                "owner_id,status,asset_id");
        assertIndex(connection, "media_asset", "idx_media_asset_status_updated", false,
                "status,updated_at,asset_id");
        assertIndex(connection, "media_processing_job", "PRIMARY", true, "job_id");
        assertIndex(connection, "media_processing_job", "uk_media_processing_job_asset_version_preset", true,
                "asset_id,job_version,preset");
        assertIndex(connection, "media_processing_job", "idx_media_processing_job_status_created", false,
                "status,created_at,job_id");
        assertIndex(connection, "media_processing_job", "idx_media_job_expired", false, "status,lease_until,job_id");
        assertIndex(connection, "media_processing_job", "idx_media_job_pending_updated", false, "status,updated_at,job_id");
        assertIndex(connection, "media_request_outbox", "idx_media_outbox_due", false, "status,available_at,event_id");
        assertIndex(connection, "media_request_outbox", "PRIMARY", true, "event_id");
        assertIndex(connection, "media_request_outbox", "uk_media_request_outbox_job_version", true,
                "job_id,job_version");
        assertIndex(connection, "media_request_outbox", "idx_media_request_outbox_status_occurred", false,
                "status,occurred_at,event_id");

        String[][] constraints = {
                {"media_asset", "PRIMARY", "PRIMARY KEY"},
                {"media_asset", "uk_media_asset_provider_object_key", "UNIQUE"},
                {"media_asset", "chk_media_asset_provider", "CHECK"},
                {"media_asset", "chk_media_asset_byte_size", "CHECK"},
                {"media_asset", "chk_media_asset_content_type", "CHECK"},
                {"media_asset", "chk_media_asset_sha256", "CHECK"},
                {"media_asset", "chk_media_asset_status", "CHECK"},
                {"media_asset", "chk_media_asset_processing_status", "CHECK"},
                {"media_processing_job", "PRIMARY", "PRIMARY KEY"},
                {"media_processing_job", "uk_media_processing_job_asset_version_preset", "UNIQUE"},
                {"media_processing_job", "chk_media_processing_job_version", "CHECK"},
                {"media_processing_job", "chk_media_processing_job_attempts", "CHECK"},
                {"media_processing_job", "chk_media_processing_job_status", "CHECK"},
                {"media_processing_job", "fk_media_processing_job_asset", "FOREIGN KEY"},
                {"media_request_outbox", "PRIMARY", "PRIMARY KEY"},
                {"media_request_outbox", "uk_media_request_outbox_job_version", "UNIQUE"},
                {"media_request_outbox", "chk_media_request_outbox_version", "CHECK"},
                {"media_request_outbox", "chk_media_request_outbox_status", "CHECK"},
                {"media_request_outbox", "fk_media_request_outbox_asset", "FOREIGN KEY"},
                {"media_request_outbox", "fk_media_request_outbox_job", "FOREIGN KEY"}
        };
        for (String[] constraint : constraints) {
            assertConstraint(connection, constraint[0], constraint[1], constraint[2]);
        }
        assertForeignKey(connection, "media_processing_job", "fk_media_processing_job_asset",
                "asset_id", "media_asset", "asset_id");
        assertForeignKey(connection, "media_request_outbox", "fk_media_request_outbox_asset",
                "asset_id", "media_asset", "asset_id");
        assertForeignKey(connection, "media_request_outbox", "fk_media_request_outbox_job",
                "job_id", "media_processing_job", "job_id");

        connection.setAutoCommit(false);
        String assetId = UUID.randomUUID().toString();
        String assetObjectKey = "upgrade-media-" + UUID.randomUUID();
        String jobId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        try {
            insertMediaAsset(connection, assetId, "local", assetObjectKey, "image/png", 128,
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "READY", "NOT_REQUESTED");
            insertMediaJob(connection, jobId, assetId, 1, "thumbnail", "processor-1", "PENDING", 0);
            insertMediaRequestOutbox(connection, eventId, assetId, jobId, 1,
                    "thumbnail", "processor-1", "PENDING");
            assertThat(count(connection, "SELECT COUNT(*) FROM media_asset WHERE asset_id=?", assetId))
                    .as("valid media asset fixture")
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM media_processing_job WHERE job_id=?", jobId))
                    .as("valid media processing job fixture")
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM media_request_outbox WHERE event_id=?", eventId))
                    .as("valid media request outbox fixture")
                    .isEqualTo(1);

            assertThatThrownBy(() -> insertMediaAsset(connection, UUID.randomUUID().toString(), "local",
                    assetObjectKey, "image/png", 128,
                    "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    "READY", "NOT_REQUESTED"))
                    .as("media asset provider/object key uniqueness")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaAsset(connection, UUID.randomUUID().toString(), "gcs",
                    "invalid-provider-" + UUID.randomUUID(), "image/png", 128,
                    "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    "READY", "NOT_REQUESTED"))
                    .as("media asset provider check")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaAsset(connection, UUID.randomUUID().toString(), "local",
                    "invalid-size-" + UUID.randomUUID(), "image/png", 0,
                    "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    "READY", "NOT_REQUESTED"))
                    .as("media asset byte size check")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaAsset(connection, UUID.randomUUID().toString(), "local",
                    "invalid-type-" + UUID.randomUUID(), "text/plain", 128,
                    "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    "READY", "NOT_REQUESTED"))
                    .as("media asset content type check")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaAsset(connection, UUID.randomUUID().toString(), "local",
                    "invalid-sha-" + UUID.randomUUID(), "image/png", 128, "not-a-sha",
                    "READY", "NOT_REQUESTED"))
                    .as("media asset sha256 check")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaAsset(connection, UUID.randomUUID().toString(), "local",
                    "invalid-status-" + UUID.randomUUID(), "image/png", 128,
                    "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    "BROKEN", "NOT_REQUESTED"))
                    .as("media asset status check")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaAsset(connection, UUID.randomUUID().toString(), "local",
                    "invalid-processing-" + UUID.randomUUID(), "image/png", 128,
                    "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    "READY", "BROKEN"))
                    .as("media asset processing status check")
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertMediaJob(connection, UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), 1, "thumbnail", "processor-1", "PENDING", 0))
                    .as("media processing job asset foreign key")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaJob(connection, UUID.randomUUID().toString(), assetId,
                    1, "thumbnail", "processor-1", "PENDING", 0))
                    .as("media processing job asset/version/preset uniqueness")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaJob(connection, UUID.randomUUID().toString(), assetId,
                    0, "invalid-version", "processor-1", "PENDING", 0))
                    .as("media processing job version check")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaJob(connection, UUID.randomUUID().toString(), assetId,
                    2, "invalid-attempts", "processor-1", "PENDING", -1))
                    .as("media processing job attempts check")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaJob(connection, UUID.randomUUID().toString(), assetId,
                    2, "invalid-status", "processor-1", "BROKEN", 0))
                    .as("media processing job status check")
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertMediaRequestOutbox(connection, UUID.randomUUID().toString(),
                    assetId, jobId, 1, "thumbnail", "processor-1", "PENDING"))
                    .as("media request outbox job/version uniqueness")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaRequestOutbox(connection, UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), jobId, 2, "thumbnail-v2", "processor-1", "PENDING"))
                    .as("media request outbox asset foreign key")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaRequestOutbox(connection, UUID.randomUUID().toString(),
                    assetId, UUID.randomUUID().toString(), 2, "thumbnail", "processor-1", "PENDING"))
                    .as("media request outbox job foreign key")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaRequestOutbox(connection, UUID.randomUUID().toString(),
                    assetId, jobId, 0, "invalid-version", "processor-1", "PENDING"))
                    .as("media request outbox version check")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMediaRequestOutbox(connection, UUID.randomUUID().toString(),
                    assetId, jobId, 2, "invalid-status", "processor-1", "BROKEN"))
                    .as("media request outbox status check")
                    .isInstanceOf(SQLException.class);
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }

        assertThat(count(connection, "SELECT COUNT(*) FROM media_asset"))
                .as("media asset fixtures are rolled back")
                .isZero();
        assertThat(count(connection, "SELECT COUNT(*) FROM media_processing_job"))
                .as("media processing job fixtures are rolled back")
                .isZero();
        assertThat(count(connection, "SELECT COUNT(*) FROM media_request_outbox"))
                .as("media request outbox fixtures are rolled back")
                .isZero();
    }

    private static void assertTable(Connection connection, String tableName) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=? AND table_type='BASE TABLE'", tableName))
                .as("media registry table " + tableName)
                .isEqualTo(1);
    }

    private static void assertColumn(Connection connection, String tableName, String columnName,
                                     String expectedDataType) throws SQLException {
        assertColumn(connection, tableName, columnName, expectedDataType, false);
    }

    private static void assertColumn(Connection connection, String tableName, String columnName,
                                     String expectedDataType, boolean expectedNullable) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT data_type,is_nullable FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet result = statement.executeQuery()) {
                String columnDescription = tableName + "." + columnName;
                assertThat(result.next()).as(columnDescription + " column").isTrue();
                assertThat(result.getString("data_type")).as(columnDescription + " data type")
                        .isEqualTo(expectedDataType);
                assertThat(result.getString("is_nullable")).as(columnDescription + " nullable")
                        .isEqualTo(expectedNullable ? "YES" : "NO");
            }
        }
    }

    private static void assertConstraint(Connection connection, String tableName, String constraintName,
                                         String constraintType) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=? "
                + "AND constraint_type=?", tableName, constraintName, constraintType))
                .as(tableName + " constraint " + constraintName)
                .isEqualTo(1);
    }

    private static void assertForeignKey(Connection connection, String tableName, String constraintName,
                                         String columnName, String referencedTable, String referencedColumn)
            throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.key_column_usage "
                + "WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=? "
                + "AND column_name=? AND referenced_table_name=? AND referenced_column_name=?",
                tableName, constraintName, columnName, referencedTable, referencedColumn))
                .as(tableName + " foreign key " + constraintName)
                .isEqualTo(1);
    }

    private static void insertMediaAsset(Connection connection, String assetId, String provider,
                                         String objectKey, String contentType, long byteSize, String sha256,
                                         String status, String processingStatus) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO media_asset "
                        + "(asset_id,owner_id,provider,object_key,url,content_type,byte_size,sha256,status,"
                        + "processing_status,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))")) {
            statement.setString(1, assetId);
            statement.setLong(2, 42L);
            statement.setString(3, provider);
            statement.setString(4, objectKey);
            statement.setString(5, "https://example.test/media/" + assetId);
            statement.setString(6, contentType);
            statement.setLong(7, byteSize);
            statement.setString(8, sha256);
            statement.setString(9, status);
            statement.setString(10, processingStatus);
            statement.executeUpdate();
        }
    }

    private static void insertMediaJob(Connection connection, String jobId, String assetId, int jobVersion,
                                       String preset, String processorVersion, String status, int attempts)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO media_processing_job "
                        + "(job_id,asset_id,job_version,preset,processor_version,status,attempts,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))")) {
            statement.setString(1, jobId);
            statement.setString(2, assetId);
            statement.setInt(3, jobVersion);
            statement.setString(4, preset);
            statement.setString(5, processorVersion);
            statement.setString(6, status);
            statement.setInt(7, attempts);
            statement.executeUpdate();
        }
    }

    private static void insertMediaRequestOutbox(Connection connection, String eventId, String assetId,
                                                  String jobId, int jobVersion, String preset,
                                                  String processorVersion, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO media_request_outbox "
                        + "(event_id,asset_id,job_id,job_version,preset,processor_version,occurred_at,status) "
                        + "VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?)")) {
            statement.setString(1, eventId);
            statement.setString(2, assetId);
            statement.setString(3, jobId);
            statement.setInt(4, jobVersion);
            statement.setString(5, preset);
            statement.setString(6, processorVersion);
            statement.setString(7, status);
            statement.executeUpdate();
        }
    }

    private static void assertIndex(Connection connection, String tableName, String indexName,
                                    boolean unique, String expectedColumns) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name=? "
                + "AND LOWER(index_name)=LOWER(?)", tableName, indexName))
                .as(tableName + " index " + indexName)
                .isEqualTo(1);
        if (unique) {
            assertThat(count(connection, "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name=? "
                    + "AND LOWER(index_name)=LOWER(?) AND non_unique=0", tableName, indexName))
                    .as(tableName + " unique index " + indexName)
                    .isEqualTo(1);
        }
        if (expectedColumns != null) {
            assertThat(stringValue(connection, "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') "
                    + "FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? "
                    + "AND LOWER(index_name)=LOWER(?)", tableName, indexName))
                    .as(tableName + " index columns " + indexName)
                    .isEqualTo(expectedColumns);
        }
    }

    private static String stringValue(Connection connection, String sql, Object... parameters)
            throws SQLException {
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

}
