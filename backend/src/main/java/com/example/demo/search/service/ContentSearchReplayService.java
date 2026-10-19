package com.example.demo.search.service;

import com.example.demo.threads.event.service.ContentEventPublisherTransport;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class ContentSearchReplayService {
    private static final String FAILED_QUEUE = "content.search.failed.v1";
    private final ConnectionFactory connections;
    private final ContentEventPublisherTransport publisher;
    private final ContentSearchConsumer consumer;

    public ContentSearchReplayService(ConnectionFactory connections, ContentEventPublisherTransport publisher,
                                     ContentSearchConsumer consumer) {
        this.connections = connections;
        this.publisher = publisher;
        this.consumer = consumer;
    }

    public ReplayResult replay(int limit) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Replay limit must be between 1 and 20");
        }
        Connection connection = connections.createConnection();
        Channel channel = null;
        int published = 0;
        int quarantined = 0;
        String failureCode = null;
        try {
            channel = connection.createChannel(false);
            for (int attempt = 0; attempt < limit; attempt++) {
                var failed = channel.basicGet(FAILED_QUEUE, false);
                if (failed == null) {
                    break;
                }
                try {
                    var event = consumer.decode(failed.getBody());
                    publisher.publish(event);
                    // The original DLQ delivery is retained until the replacement is confirmed and routable.
                    channel.basicAck(failed.getEnvelope().getDeliveryTag(), false);
                    published++;
                } catch (RuntimeException failure) {
                    if (failure instanceof InvalidContentEventException) {
                        try {
                            publisher.quarantine(failed.getBody());
                            channel.basicAck(failed.getEnvelope().getDeliveryTag(), false);
                            quarantined++;
                            continue;
                        } catch (RuntimeException quarantineFailure) {
                            failureCode = "QUARANTINE_UNAVAILABLE";
                        }
                    } else {
                        failureCode = "BROKER_UNAVAILABLE";
                    }
                    channel.basicNack(failed.getEnvelope().getDeliveryTag(), false, true);
                    break;
                }
            }
            int remaining = channel.queueDeclarePassive(FAILED_QUEUE).getMessageCount();
            return new ReplayResult(published, remaining, quarantined, failureCode);
        } catch (IOException failure) {
            throw new IllegalStateException("DLQ_REPLAY_UNAVAILABLE");
        } finally {
            if (channel != null) {
                try { channel.close(); } catch (Exception ignored) { }
            }
            connection.close();
        }
    }

    public record ReplayResult(int published, int remaining, int quarantined, String failureCode) { }
}
