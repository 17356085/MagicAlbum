package com.example.demo.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
public class MediaRequestPublisher {
    private static final Logger log = LoggerFactory.getLogger(MediaRequestPublisher.class);
    private final MediaDeliveryTransactions deliveries;
    private final RabbitTemplate template;
    private final ObjectMapper mapper;

    public MediaRequestPublisher(MediaDeliveryTransactions deliveries, ConnectionFactory factory) {
        if (!(factory instanceof CachingConnectionFactory caching) || !caching.isPublisherConfirms()
                || caching.isSimplePublisherConfirms() || !caching.isPublisherReturns()) {
            throw new IllegalStateException("Media delivery requires correlated confirms and publisher returns");
        }
        caching.getRabbitConnectionFactory().setConnectionTimeout(5000);
        caching.getRabbitConnectionFactory().setHandshakeTimeout(5000);
        caching.getRabbitConnectionFactory().setChannelRpcTimeout(5000);
        this.deliveries = deliveries;
        // The media protocol must work when the optional content/search pipeline is disabled.
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.template = new RabbitTemplate(factory);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> { });
    }

    @Scheduled(fixedDelayString = "${app.media.publish-delay-ms:1000}", scheduler = "mediaPublishScheduler")
    public void publishDue() {
        for (int count = 0; count < 10; count++) {
            var delivery = deliveries.claim();
            if (delivery.isEmpty()) return;
            var current = delivery.get();
            try {
                MessageProperties properties = new MessageProperties();
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setContentType("application/json");
                properties.setMessageId(current.eventId());
                CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
                template.send("media.jobs.v1", "process", new Message(mapper.writeValueAsBytes(current.envelope()), properties), correlation);
                var confirmed = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirmed.ack() || correlation.getReturned() != null) throw new IllegalStateException("Unconfirmed media delivery");
                deliveries.published(current);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                deliveries.failed(current);
                return;
            } catch (Exception failure) {
                deliveries.failed(current);
                log.warn("media_publish_retry eventId={} reason=PUBLISH_UNCONFIRMED", current.eventId());
                return;
            }
        }
    }
}
