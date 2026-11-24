package com.example.demo.chat;

import com.example.demo.chat.service.ChatChangePublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
            var meters = new SimpleMeterRegistry();
            var beans = new StaticListableBeanFactory();
            beans.addBean("rabbit", rabbit);
            var publisher = new ChatChangePublisher(beans.getBeanProvider(RabbitTemplate.class), true, meters);
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

    @Test
    void recordsRabbitFailuresSeparatelyFromQueueRejections() {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
            var rabbit = mock(RabbitTemplate.class);
            var meters = new SimpleMeterRegistry();
            var beans = new StaticListableBeanFactory();
            beans.addBean("rabbit", rabbit);
            var publisher = new ChatChangePublisher(beans.getBeanProvider(RabbitTemplate.class), true, meters);
            doThrow(new AmqpConnectException(new IOException("rabbit is offline")))
                    .when(rabbit).send(eq(ChatChangePublisher.EXCHANGE), eq(""), any(Message.class));

            transaction.executeWithoutResult(status -> publisher.changed(() -> List.of(7L)));

            verify(rabbit, timeout(2000)).send(eq(ChatChangePublisher.EXCHANGE), eq(""), any(Message.class));
            assertThat(dropped(meters, "rabbit_failure")).isEqualTo(1.0);
            assertThat(dropped(meters, "queue_full")).isZero();
        } finally {
            database.shutdown();
        }
    }

    @Test
    void recordsQueueFullAndResumesAfterTheBoundedQueueDrains() throws Exception {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        var workerStarted = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        try {
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
            var rabbit = mock(RabbitTemplate.class);
            var meters = new SimpleMeterRegistry();
            var beans = new StaticListableBeanFactory();
            beans.addBean("rabbit", rabbit);
            var publisher = new ChatChangePublisher(beans.getBeanProvider(RabbitTemplate.class), true, meters);
            var sends = new AtomicInteger();
            doAnswer(invocation -> {
                if (sends.getAndIncrement() == 0) {
                    workerStarted.countDown();
                    releaseWorker.await(5, TimeUnit.SECONDS);
                }
                return null;
            }).when(rabbit).send(eq(ChatChangePublisher.EXCHANGE), eq(""), any(Message.class));

            transaction.executeWithoutResult(status -> publisher.changed(() -> List.of(1L)));
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            for (long recipient = 2; recipient <= 257; recipient++) {
                long queuedRecipient = recipient;
                transaction.executeWithoutResult(status -> publisher.changed(() -> List.of(queuedRecipient)));
            }
            assertThatCode(() -> transaction.executeWithoutResult(status -> publisher.changed(() -> List.of(258L))))
                    .doesNotThrowAnyException();

            assertThat(dropped(meters, "queue_full")).isEqualTo(1.0);
            assertThat(dropped(meters, "rabbit_failure")).isZero();

            releaseWorker.countDown();
            verify(rabbit, timeout(5000).times(257))
                    .send(eq(ChatChangePublisher.EXCHANGE), eq(""), any(Message.class));

            transaction.executeWithoutResult(status -> publisher.changed(() -> List.of(259L)));
            verify(rabbit, timeout(2000).times(258))
                    .send(eq(ChatChangePublisher.EXCHANGE), eq(""), any(Message.class));
        } finally {
            releaseWorker.countDown();
            database.shutdown();
        }
    }

    private static double dropped(SimpleMeterRegistry meters, String reason) {
        return meters.get(ChatChangePublisher.DROPPED_METRIC).tag("reason", reason).counter().count();
    }
}
