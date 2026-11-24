package com.example.demo.search.service;

import com.example.demo.search.port.SearchVersionConflictException;
import com.example.demo.threads.event.dto.ContentEventMessage;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class ContentSearchConsumer {
    private static final Logger log = LoggerFactory.getLogger(ContentSearchConsumer.class);
    private final ContentSearchEventDecoder decoder;
    private final ContentSearchProjection projection;
    private final ContentSearchPipelineMetrics metrics;
    private final int maxAttempts;
    private final long backoffMillis;

    public ContentSearchConsumer(ContentSearchEventDecoder decoder, ContentSearchProjection projection,
                                 ContentSearchPipelineMetrics metrics,
                                 @Value("${app.search.consumer.max-attempts:3}") int maxAttempts,
                                 @Value("${app.search.consumer.backoff-ms:250}") long backoffMillis) {
        if (maxAttempts < 1 || maxAttempts > 5 || backoffMillis < 0 || backoffMillis > 2000) {
            throw new IllegalArgumentException("Invalid content consumer retry bounds");
        }
        this.decoder = decoder;
        this.projection = projection;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
    }

    @RabbitListener(queues = "content.search.v1", containerFactory = "contentSearchListenerFactory")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        ContentEventMessage event;
        try {
            event = decoder.decode(message.getBody());
        } catch (RuntimeException invalid) {
            log.warn("content_search_quarantined code=INVALID_ENVELOPE");
            rejectForReplay(channel, deliveryTag);
            return;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                projection.apply(event);
                channel.basicAck(deliveryTag, false);
                return;
            } catch (InvalidContentEventException | SearchVersionConflictException conflict) {
                log.warn("content_search_quarantined eventId={} aggregateId={} version={} code=VERSION_OR_SCHEMA_CONFLICT",
                        event.eventId(), event.aggregateId(), event.aggregateVersion());
                rejectForReplay(channel, deliveryTag);
                return;
            } catch (RuntimeException dependencyFailure) {
                if (attempt == maxAttempts) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(backoffMillis * attempt, 2000));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.warn("content_search_quarantined eventId={} aggregateId={} version={} code=DEPENDENCY_UNAVAILABLE",
                event.eventId(), event.aggregateId(), event.aggregateVersion());
        rejectForReplay(channel, deliveryTag);
    }

    private void rejectForReplay(Channel channel, long deliveryTag) throws IOException {
        channel.basicNack(deliveryTag, false, false);
        metrics.failed();
    }
}
