package com.example.notification.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(
        name = "spring.flyway.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class FlywayConfiguration {
    @Bean(initMethod = "migrate")
    public Flyway flyway(
            DataSource dataSource,
            @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
            @Value("${spring.flyway.table:notification_flyway_schema_history}") String table
    ) {
        String[] configuredLocations = locations.split("\\s*,\\s*");
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(configuredLocations)
                .table(table)
                .cleanDisabled(true)
                .load();
    }
}
