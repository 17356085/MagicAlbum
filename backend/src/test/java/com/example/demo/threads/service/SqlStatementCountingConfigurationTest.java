package com.example.demo.threads.service;

import com.example.demo.threads.service.SqlStatementCountingConfiguration.PreparedStatementCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlStatementCountingConfigurationTest {
    @Test
    void counterIsIndependentPerContextAndPerThreadWithReset() throws Exception {
        try (AnnotationConfigApplicationContext first = context("sql_counter_first");
                AnnotationConfigApplicationContext second = context("sql_counter_second")) {
            PreparedStatementCounter firstCounter = first.getBean(PreparedStatementCounter.class);
            PreparedStatementCounter secondCounter = second.getBean(PreparedStatementCounter.class);
            JdbcTemplate firstJdbc = first.getBean(JdbcTemplate.class);
            JdbcTemplate secondJdbc = second.getBean(JdbcTemplate.class);

            assertThat(first.getBean(DataSource.class))
                    .isInstanceOf(SqlStatementCountingConfiguration.CountingDataSource.class);
            assertThat(firstCounter).isNotSameAs(secondCounter);

            firstJdbc.queryForObject("select ?", Integer.class, 1);
            assertThat(firstCounter.count()).isEqualTo(1L);
            assertThat(secondCounter.count()).isZero();

            secondJdbc.queryForObject("select ?", Integer.class, 1);
            assertThat(secondCounter.count()).isEqualTo(1L);
            assertThat(firstCounter.count()).isEqualTo(1L);

            firstCounter.reset();
            DataSource firstDataSource = first.getBean(DataSource.class);
            try (Connection connection = firstDataSource.getConnection("sa", "");
                    var statement = connection.prepareStatement("select ?")) {
                statement.setInt(1, 1);
                try (var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                }
            }
            assertThat(firstCounter.count()).isEqualTo(1L);

            BeanPostProcessor postProcessor = first.getBean(
                    "dataSourcePreparedStatementCounter", BeanPostProcessor.class);
            DataSource secondPass = (DataSource) postProcessor.postProcessAfterInitialization(
                    firstDataSource, "secondPass");
            assertThat(secondPass).isSameAs(firstDataSource);
            new JdbcTemplate(secondPass).queryForObject("select ?", Integer.class, 1);
            assertThat(firstCounter.count()).isEqualTo(2L);

            firstCounter.reset();
            assertThat(firstCounter.count()).isZero();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                List<Long> workerCounts = executor.submit(() -> {
                    firstJdbc.queryForObject("select ?", Integer.class, 1);
                    long afterQuery = firstCounter.count();
                    firstCounter.reset();
                    long afterReset = firstCounter.count();
                    firstJdbc.queryForObject("select ?", Integer.class, 1);
                    return List.of(afterQuery, afterReset, firstCounter.count());
                }).get();

                assertThat(workerCounts).containsExactly(1L, 0L, 1L);
                assertThat(firstCounter.count()).isZero();
                assertThat(secondCounter.count()).isEqualTo(1L);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void failedPrepareDoesNotCountAndPreservesSqlException() throws Exception {
        try (AnnotationConfigApplicationContext context = context("sql_counter_failure")) {
            PreparedStatementCounter counter = context.getBean(PreparedStatementCounter.class);
            DataSource dataSource = context.getBean(DataSource.class);

            try (Connection connection = dataSource.getConnection()) {
                assertThatThrownBy(() -> connection.prepareStatement("SELEKT 1"))
                        .isInstanceOf(SQLException.class)
                        .isNotInstanceOf(InvocationTargetException.class);
            }
            assertThat(counter.count()).isZero();

            try (Connection connection = dataSource.getConnection();
                    var statement = connection.prepareStatement("select 1")) {
                // A successful prepare is counted after the driver returns the statement.
            }
            assertThat(counter.count()).isEqualTo(1L);
        }
    }

    private static AnnotationConfigApplicationContext context(String databaseName) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(SqlStatementCountingConfiguration.class);
        context.registerBean(DataSource.class, () -> new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL", "sa", ""));
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(context.getBean(DataSource.class)));
        context.refresh();
        return context;
    }
}
