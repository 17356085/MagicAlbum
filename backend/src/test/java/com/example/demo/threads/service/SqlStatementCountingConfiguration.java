package com.example.demo.threads.service;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.LongAdder;

@TestConfiguration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
final class SqlStatementCountingConfiguration {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static PreparedStatementCounter preparedStatementCounter() {
        return new PreparedStatementCounter();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static BeanPostProcessor dataSourcePreparedStatementCounter(PreparedStatementCounter counter) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSource dataSource)
                        || bean instanceof CountingDataSource) {
                    return bean;
                }
                return new CountingDataSource(dataSource, counter);
            }
        };
    }

    static final class PreparedStatementCounter {
        private final ThreadLocal<LongAdder> statements = ThreadLocal.withInitial(LongAdder::new);

        void reset() {
            statements.get().reset();
        }

        long count() {
            return statements.get().sum();
        }

        private void record() {
            statements.get().increment();
        }
    }

    static final class CountingDataSource extends DelegatingDataSource {
        private final PreparedStatementCounter counter;

        CountingDataSource(DataSource targetDataSource, PreparedStatementCounter counter) {
            super(targetDataSource);
            this.counter = counter;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            InvocationHandler handler = (proxy, method, arguments) -> invoke(connection, method, arguments);
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    handler);
        }

        private Object invoke(Connection target, Method method, Object[] arguments) throws Throwable {
            try {
                Object result = method.invoke(target, arguments);
                if ("prepareStatement".equals(method.getName())) {
                    counter.record();
                }
                return result;
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
