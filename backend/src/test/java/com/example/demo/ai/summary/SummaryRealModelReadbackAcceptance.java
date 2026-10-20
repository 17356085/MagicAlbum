package com.example.demo.ai.summary;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Run in a second Maven/JVM after the external application context has shut down. No model calls. */
class SummaryRealModelReadbackAcceptance {
    @Test
    void freshProcessReadsBothSummariesAndTheirCommittedVersionAndCost() throws Exception {
        var directory = SummaryRealModelAcceptance.output();
        var source = SummaryRealModelAcceptance.JSON.readTree(Files.readString(directory.resolve("external-model.json")));
        assertThat(source.path("cases").size()).isEqualTo(2);
        var rows = new ArrayList<Map<String, Object>>();
        try (var connection = DriverManager.getConnection(SummaryRealModelAcceptance.databaseUrl() + ";IFEXISTS=TRUE", "sa", "")) {
            for (var expected : source.path("cases")) {
                try (var query = connection.prepareStatement("SELECT t.summary,t.summary_status,t.content_version,t.summary_task_id,s.status,s.content_version FROM threads t JOIN ai_summary_task s ON s.task_id=t.summary_task_id WHERE t.id=?")) {
                    query.setLong(1, expected.path("threadId").asLong());
                    try (var result = query.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(2)).isEqualTo("COMPLETED"); assertThat(result.getString(5)).isEqualTo("COMPLETED");
                        assertThat(result.getLong(3)).isEqualTo(expected.path("contentVersion").asLong()).isEqualTo(result.getLong(6));
                        assertThat(result.getString(4)).isEqualTo(expected.path("taskId").asText());
                        assertThat(SummaryRealModelAcceptance.sha(result.getString(1))).isEqualTo(expected.path("summarySha256").asText());
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("threadId", expected.path("threadId").asLong()); row.put("taskId", result.getString(4)); row.put("summarySha256", SummaryRealModelAcceptance.sha(result.getString(1)));
                        try (var costs = connection.prepareStatement("SELECT COUNT(*),COALESCE(SUM(charged_tokens),0) FROM ai_summary_attempt WHERE task_id=?")) {
                            costs.setString(1, result.getString(4));
                            try (var ledger = costs.executeQuery()) {
                                assertThat(ledger.next()).isTrue();
                                assertThat(ledger.getInt(1)).isEqualTo(expected.path("attempts").asInt());
                                assertThat(ledger.getLong(2)).isEqualTo(expected.path("chargedTokens").asLong());
                                row.put("attempts", ledger.getInt(1)); row.put("chargedTokens", ledger.getLong(2));
                            }
                        }
                        try (var budgets = connection.prepareStatement("SELECT b.charged_tokens,(SELECT COALESCE(SUM(a.charged_tokens),0) FROM ai_summary_attempt a JOIN ai_summary_task s ON s.task_id=a.task_id WHERE s.triggered_by=b.user_id AND a.budget_day=b.budget_day) FROM ai_summary_budget b WHERE b.user_id=(SELECT triggered_by FROM ai_summary_task WHERE task_id=?)")) {
                            budgets.setString(1, result.getString(4));
                            try (var ledger = budgets.executeQuery()) {
                                int days = 0;
                                while (ledger.next()) { assertThat(ledger.getLong(1)).isEqualTo(ledger.getLong(2)); days++; }
                                assertThat(days).isGreaterThan(0);
                                row.put("budgetDaysReconciled", days);
                            }
                        }
                        rows.add(row);
                    }
                }
            }
        }
        SummaryRealModelAcceptance.JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("fresh-process-readback.json").toFile(),
                Map.of("passed", true, "separateJvm", true, "databaseKind", "isolated-file-H2", "cases", rows));
    }
}
