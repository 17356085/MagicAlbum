package com.example.demo.threads.event.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Opt-in Kafka controls for the stable content-event envelope.
 *
 * <p>The containing Kafka configuration is conditional on {@code enabled}, so
 * these defaults do not create a producer or consumer connection by
 * themselves.</p>
 */
@ConfigurationProperties(prefix = "app.content.events.kafka")
public class KafkaContentEventProperties {
    private boolean enabled;
    private String bootstrapServers = "localhost:9092";
    private String topic = "content.events.v1";
    private String groupId = "content-search-v1";
    private String clientId = "bluealbum-content-events";
    private Duration maxBlockTimeout = Duration.ofSeconds(5);
    private Duration sendTimeout = Duration.ofSeconds(5);
    private Duration pollTimeout = Duration.ofSeconds(1);
    private Duration closeTimeout = Duration.ofSeconds(5);

    public void validate() {
        requireText(bootstrapServers, "bootstrap-servers");
        requireText(topic, "topic");
        requireText(groupId, "group-id");
        requireText(clientId, "client-id");
        requireDuration(maxBlockTimeout, "max-block-timeout");
        requireDuration(sendTimeout, "send-timeout");
        requireDuration(pollTimeout, "poll-timeout");
        requireDuration(closeTimeout, "close-timeout");
        requireKafkaDuration(maxBlockTimeout, "max-block-timeout");
        requireKafkaDuration(sendTimeout, "send-timeout");
        requireKafkaDuration(pollTimeout, "poll-timeout");
        requireKafkaDuration(closeTimeout, "close-timeout");
        try {
            if (maxBlockTimeout.plus(sendTimeout).compareTo(Duration.ofSeconds(30)) >= 0) {
                throw new IllegalArgumentException(
                        "app.content.events.kafka.max-block-timeout plus send-timeout must be below the 30-second SQL lease");
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("app.content.events.kafka timeout budget is too large", overflow);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("app.content.events.kafka." + name + " must not be blank");
        }
    }

    private static void requireDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.content.events.kafka." + name + " must be positive");
        }
    }

    private static void requireKafkaDuration(Duration value, String name) {
        try {
            long millis = value.toMillis();
            if (millis >= 1 && millis <= Integer.MAX_VALUE) {
                return;
            }
        } catch (ArithmeticException overflow) {
            // Fall through to the same actionable configuration error.
        }
        throw new IllegalArgumentException(
                "app.content.events.kafka." + name + " must fit a Kafka millisecond timeout");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public Duration getMaxBlockTimeout() {
        return maxBlockTimeout;
    }

    public void setMaxBlockTimeout(Duration maxBlockTimeout) {
        this.maxBlockTimeout = maxBlockTimeout;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getPollTimeout() {
        return pollTimeout;
    }

    public void setPollTimeout(Duration pollTimeout) {
        this.pollTimeout = pollTimeout;
    }

    public Duration getCloseTimeout() {
        return closeTimeout;
    }

    public void setCloseTimeout(Duration closeTimeout) {
        this.closeTimeout = closeTimeout;
    }
}
