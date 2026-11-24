package com.example.demo.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class ChatChangePublisher {
    public static final String EXCHANGE = "chat.realtime.changed.v1";

    private static final Logger LOG = LoggerFactory.getLogger(ChatChangePublisher.class);
    private final ObjectProvider<RabbitTemplate> rabbit;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon().name("chat-realtime").factory(),
            new ThreadPoolExecutor.DiscardPolicy());

    public ChatChangePublisher(ObjectProvider<RabbitTemplate> rabbit,
                               @Value("${app.rabbit.enabled:false}") boolean enabled) {
        this.rabbit = rabbit;
        this.enabled = enabled;
    }

    public void changed(Supplier<List<Long>> recipients) {
        if (!enabled) return;
        List<Long> users = List.copyOf(recipients.get());
        if (users.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> publish(users));
            }
        });
    }

    private void publish(List<Long> users) {
        try {
            RabbitTemplate template = rabbit.getIfAvailable();
            if (template == null) return;
            template.send(EXCHANGE, "", MessageBuilder.withBody(json.writeValueAsBytes(users))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT)
                    .build());
        } catch (RuntimeException | JsonProcessingException unavailable) {
            LOG.debug("Chat realtime signal was skipped", unavailable);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
