package com.example.demo.ai.summary.service;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SummaryTaskPublisher {
    public static final String QUEUE = "ai.summary.v1";
    private static final Logger LOG = LoggerFactory.getLogger(SummaryTaskPublisher.class);
    private final RabbitTemplate rabbit;
    private final boolean enabled;

    public SummaryTaskPublisher(ObjectProvider<RabbitTemplate> rabbit,
                                @Value("${app.rabbit.enabled:false}") boolean enabled) {
        this.rabbit = rabbit.getIfAvailable();
        this.enabled = enabled;
    }

    public boolean available() { return enabled && rabbit != null; }

    public boolean publish(String id) {
        if (!available()) return false;
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        try {
            rabbit.send(QUEUE, new Message(id.getBytes(StandardCharsets.UTF_8), properties));
            return true;
        } catch (RuntimeException ignored) {
            // Durable PENDING intent is recovered independently of broker acknowledgement.
            LOG.warn("Summary delivery deferred for task {}", id);
            return false;
        }
    }
}
