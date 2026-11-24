package com.example.demo.chat;

import com.example.demo.chat.service.ChatChangePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatChangePublisherTest {
    @Test
    void dispatchesOnlyCommittedChanges() {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
            var rabbit = mock(RabbitTemplate.class);
            var beans = new StaticListableBeanFactory();
            beans.addBean("rabbit", rabbit);
            var publisher = new ChatChangePublisher(beans.getBeanProvider(RabbitTemplate.class), true);
            transaction.executeWithoutResult(status -> {
                publisher.changed(() -> List.of(99L));
                status.setRollbackOnly();
            });
            var signal = org.mockito.ArgumentCaptor.forClass(Message.class);
            transaction.executeWithoutResult(status -> {
                publisher.changed(() -> List.of(7L, 42L));
                verifyNoInteractions(rabbit);
            });
            verify(rabbit, timeout(2000)).send(eq(ChatChangePublisher.EXCHANGE), eq(""), signal.capture());
            assertThat(signal.getValue().getBody()).asString(java.nio.charset.StandardCharsets.UTF_8).isEqualTo("[7,42]");
        } finally {
            database.shutdown();
        }
    }
}
