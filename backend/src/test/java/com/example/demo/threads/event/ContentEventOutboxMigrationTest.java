package com.example.demo.threads.event;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the shipped MySQL DDL in H2 compatibility mode only. */
class ContentEventOutboxMigrationTest {
    @Test
    void migration_defaults_existing_thread_versions_and_enforces_event_uniqueness() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:content-event-ddl;MODE=MySQL;DATABASE_TO_UPPER=false", "sa", "")) {
            connection.createStatement().execute("""
                    CREATE TABLE threads (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        content_md VARCHAR(255) NOT NULL
                    )
                    """);
            connection.createStatement().execute("INSERT INTO threads (content_md) VALUES ('legacy')");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/mysql/V20261018_2__content_event_outbox.sql"));

            try (var result = connection.createStatement().executeQuery(
                    "SELECT content_version FROM threads WHERE id=1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }

            String insert = """
                    INSERT INTO content_event_outbox
                    (event_id,schema_version,aggregate_type,aggregate_id,aggregate_version,event_type,
                     occurred_at,request_id,status)
                    VALUES ('event-1',1,'THREAD',1,1,'THREAD_UPSERTED',CURRENT_TIMESTAMP,'req-1','PENDING')
                    """;
            connection.createStatement().execute(insert);
            assertThatThrownBy(() -> connection.createStatement().execute(insert))
                    .isInstanceOf(SQLException.class);

            String sameAggregateVersion = insert.replace("'event-1'", "'event-2'");
            assertThatThrownBy(() -> connection.createStatement().execute(sameAggregateVersion))
                    .isInstanceOf(SQLException.class);

            try (var result = connection.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                            + "WHERE UPPER(TABLE_NAME)='CONTENT_EVENT_OUTBOX' "
                            + "AND UPPER(INDEX_NAME)='IDX_CONTENT_EVENT_OUTBOX_STATUS_ID'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }
}
