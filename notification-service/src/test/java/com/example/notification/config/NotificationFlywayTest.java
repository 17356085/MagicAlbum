package com.example.notification.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class NotificationFlywayTest {
    private static final String DATABASE_URL = "jdbc:h2:mem:notification_flyway_" + UUID.randomUUID();

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) throws SQLException {
        try (var connection = DriverManager.getConnection(DATABASE_URL + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER IF NOT EXISTS notification_app PASSWORD 'fixture-runtime'");
        }
        properties.add("spring.datasource.url", () -> DATABASE_URL);
        properties.add("spring.datasource.username", () -> "notification_app");
        properties.add("spring.datasource.password", () -> "fixture-runtime");
        properties.add("spring.flyway.user", () -> "sa");
        properties.add("spring.flyway.password", () -> "");
        properties.add("spring.flyway.target", () -> "3");
    }

    @Test
    void appliesOnlyRequestedMigrationsWithSeparateCredentials() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(flyway.getConfiguration().getTable()).isEqualTo("notification_flyway_schema_history");
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT CURRENT_USER", String.class)).isEqualTo("NOTIFICATION_APP");
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE privilege_probe (id INTEGER)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Not enough rights");
    }
}
