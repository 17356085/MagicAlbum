package com.example.demo.threads.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSearchControlMigrationTest {
    @Test
    void migrationCreatesOneControlRowWithSafeDefaults() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:content-search-control-ddl;MODE=MySQL;DATABASE_TO_UPPER=false",
                "sa", "")) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/mysql/V20261019_2__content_search_control.sql"));

            try (var result = connection.createStatement().executeQuery(
                    "SELECT id, pause_owner, pause_until, active_index, ready "
                            + "FROM content_search_control")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("id")).isEqualTo(1L);
                assertThat(result.getString("pause_owner")).isNull();
                assertThat(result.getTimestamp("pause_until")).isNull();
                assertThat(result.getString("active_index")).isNull();
                assertThat(result.getBoolean("ready")).isFalse();
                assertThat(result.next()).isFalse();
            }
        }
    }
}
