package com.example.demo.ai.summary.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rabbit.enabled", havingValue = "true")
public class SummaryQueueConsumer {
    private final SummaryTaskRunner runner;
    public SummaryQueueConsumer(SummaryTaskRunner runner) { this.runner = runner; }

    @RabbitListener(queues = SummaryTaskPublisher.QUEUE, concurrency = "2")
    public void accept(Message message) {
        if (message == null || message.getBody().length != 36) return;
        String id = new String(message.getBody(), StandardCharsets.UTF_8);
        try { if (!UUID.fromString(id).toString().equals(id)) return; }
        catch (IllegalArgumentException invalid) { return; }
        runner.execute(id);
    }
}
