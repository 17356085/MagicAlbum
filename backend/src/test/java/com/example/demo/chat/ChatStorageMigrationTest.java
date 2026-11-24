package com.example.demo.chat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStorageMigrationTest {
    private static final List<String> OWNER_TABLES = List.of(
            "chat_conversations", "chat_members", "chat_messages", "chat_daily_usage", "chat_preferences");

    @Test
    void independentBaselineMatchesSharedChatColumnsAndLeavesSocialTablesOut() {
        var sharedDatabase = database();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/mysql/V20261120_1__create_chat.sql"),
                new ClassPathResource("db/migration/mysql/V20261120_2__add_chat_history_revision.sql"),
                new ClassPathResource("db/migration/mysql/V20261124_1__chat_message_recall.sql"))
                .execute(sharedDatabase);
        var targetDatabase = database();
        var flyway = Flyway.configure().dataSource(targetDatabase)
                .locations("classpath:db/chat/mysql").table("chat_flyway_schema_history").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        var source = new JdbcTemplate(sharedDatabase);
        var target = new JdbcTemplate(targetDatabase);
        assertThat(target.queryForList("""
                SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema = 'PUBLIC'
                """, String.class)).containsExactlyInAnyOrderElementsOf(
                List.of("chat_flyway_schema_history", "chat_write_locks", "chat_moderation_receipts", "chat_conversations", "chat_members",
                        "chat_messages", "chat_daily_usage", "chat_preferences"));
        for (String table : OWNER_TABLES) {
            assertThat(columns(target, table)).containsExactlyElementsOf(columns(source, table));
        }
    }

    private static List<Column> columns(JdbcTemplate jdbc, String table) {
        return jdbc.query("""
                SELECT column_name, data_type, character_maximum_length, is_nullable, column_default, is_identity
                FROM information_schema.columns WHERE table_schema = 'PUBLIC' AND table_name = ?
                ORDER BY ordinal_position
                """, (row, index) -> new Column(row.getString(1), row.getString(2), row.getObject(3),
                row.getString(4), row.getString(5), row.getString(6)), table.toUpperCase(Locale.ROOT));
    }

    private static DriverManagerDataSource database() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
    }

    private record Column(String name, String type, Object length, String nullable, String defaultValue, String identity) {}
}
