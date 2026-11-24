package com.example.demo.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    public static final String DROPPED_METRIC = "bluealbum.chat.realtime.signal.dropped";

    private static final Logger LOG = LoggerFactory.getLogger(ChatChangePublisher.class);
    private static final String QUEUE_FULL = "queue_full";
    private static final String RABBIT_FAILURE = "rabbit_failure";
    private static final String UNAVAILABLE = "unavailable";
    private static final String SHUTDOWN = "shutdown";
    private static final int QUEUE_CAPACITY = 256;
    private static final MeterRegistry FALLBACK_METRICS = new SimpleMeterRegistry();

    private final ObjectProvider<RabbitTemplate> rabbit;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();
    private final Counter queueFull;
    private final Counter rabbitFailure;
    private final Counter unavailable;
    private final Counter shutdown;
    private final ThreadPoolExecutor executor;

    @Autowired
    public ChatChangePublisher(ObjectProvider<RabbitTemplate> rabbit,
                               @Value("${app.rabbit.enabled:false}") boolean enabled,
                               ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(rabbit, enabled, meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable());
    }

    public ChatChangePublisher(ObjectProvider<RabbitTemplate> rabbit, boolean enabled) {
        this(rabbit, enabled, (MeterRegistry) null);
    }

    public ChatChangePublisher(ObjectProvider<RabbitTemplate> rabbit, boolean enabled,
                               MeterRegistry meterRegistry) {
        this.rabbit = rabbit;
        this.enabled = enabled;
        MeterRegistry registry = meterRegistry == null ? FALLBACK_METRICS : meterRegistry;
        this.queueFull = droppedCounter(registry, QUEUE_FULL);
        this.rabbitFailure = droppedCounter(registry, RABBIT_FAILURE);
        this.unavailable = droppedCounter(registry, UNAVAILABLE);
        this.shutdown = droppedCounter(registry, SHUTDOWN);
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), Thread.ofPlatform().daemon().name("chat-realtime").factory(),
                this::onRejected);
    }

    public void changed(Supplier<List<Long>> recipients) {
        if (!enabled) return;
        List<Long> users = List.copyOf(recipients.get());
        if (users.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    executor.execute(() -> publish(users));
                } catch (RuntimeException rejected) {
                    recordSubmissionFailure(rejected);
                }
            }
        });
    }

    private void publish(List<Long> users) {
        try {
            RabbitTemplate template = rabbit == null ? null : rabbit.getIfAvailable();
            if (template == null) {
                unavailable.increment();
                return;
            }
            template.send(EXCHANGE, "", MessageBuilder.withBody(json.writeValueAsBytes(users))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT)
                    .build());
        } catch (JsonProcessingException serializationFailure) {
            LOG.debug("Chat realtime signal could not be serialized", serializationFailure);
        } catch (RuntimeException rabbitFailure) {
            this.rabbitFailure.increment();
            LOG.debug("Chat realtime signal was skipped", rabbitFailure);
        }
    }

    private void onRejected(Runnable task, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            shutdown.increment();
        } else {
            queueFull.increment();
        }
    }

    private void recordSubmissionFailure(RuntimeException rejected) {
        if (executor.isShutdown()) {
            shutdown.increment();
        } else {
            queueFull.increment();
        }
        LOG.debug("Chat realtime signal could not be queued", rejected);
    }

    private static Counter droppedCounter(MeterRegistry registry, String reason) {
        return Counter.builder(DROPPED_METRIC)
                .description("Chat realtime signals dropped before delivery")
                .tag("reason", reason)
                .register(registry);
    }

    @PreDestroy
    void stop() {
        List<Runnable> abandoned = executor.shutdownNow();
        if (!abandoned.isEmpty()) {
            shutdown.increment(abandoned.size());
        }
    }
}
