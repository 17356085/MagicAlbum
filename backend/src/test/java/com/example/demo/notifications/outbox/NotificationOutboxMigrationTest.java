package com.example.demo.notifications.outbox;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the shipped DDL in compatibility mode; target MySQL still needs deployment validation. */
class NotificationOutboxMigrationTest {
    @Test
    void migration_creates_event_uniqueness_and_user_cleanup_constraints() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:outbox-ddl;MODE=MySQL", "sa", "")) {
            connection.createStatement().execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("INSERT INTO users VALUES (1)");
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/mysql/V20261014__create_notification_outbox.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/mysql/V20261018_1__notification_request_id.sql"));
            String insert = """
                    INSERT INTO notification_outbox
                    (event_id,request_id,recipient_user_id,type,title,content,status,available_at,created_at,updated_at)
                    VALUES ('same-event','m1-request',1,'reply','title','content','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """;
            connection.createStatement().execute(insert);
            try (var result = connection.createStatement().executeQuery(
                    "SELECT request_id FROM notification_outbox WHERE event_id='same-event'")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo("m1-request");
            }
            try (var result = connection.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                            + "WHERE UPPER(TABLE_NAME)='NOTIFICATION_OUTBOX' "
                            + "AND UPPER(INDEX_NAME)='IDX_NOTIFICATION_OUTBOX_STATUS_CREATED_AT'")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            assertThatThrownBy(() -> connection.createStatement().execute(insert)).isInstanceOf(SQLException.class);
            connection.createStatement().execute("DELETE FROM users WHERE id=1");
            try (var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM notification_outbox")) {
                result.next();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }
}
