package com.example.demo.threads.event.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Runtime controls for the content event outbox publisher. */
@Component
@ConfigurationProperties(prefix = "app.content.events")
public class ContentEventProperties {
    private boolean enabled;
    private int batchSize = 20;
    private int maxAttempts = 5;
    private Duration backoffBase = Duration.ofSeconds(2);
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration confirmTimeout = Duration.ofSeconds(5);
    private Duration connectionTimeout = Duration.ofSeconds(5);
    private Duration commitMargin = Duration.ofSeconds(2);
    private int pollIntervalMs = 2_000;

    @PostConstruct
    public void validate() {
        if (batchSize < 1 || batchSize > 20) {
            throw new IllegalArgumentException("app.content.events.batch-size must be between 1 and 20");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("app.content.events.max-attempts must be between 1 and 100");
        }
        requireDuration(backoffBase, "backoff-base");
        if (backoffBase.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("app.content.events.backoff-base must be at most 60 seconds");
        }
        requireDuration(confirmTimeout, "confirm-timeout");
        requireDuration(connectionTimeout, "connection-timeout");
        requireDuration(commitMargin, "commit-margin");
        requireDuration(leaseDuration, "lease-duration");
        Duration minimumLease;
        try {
            minimumLease = confirmTimeout.plus(connectionTimeout.multipliedBy(3)).plus(commitMargin);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("app.content.events lease budget is too large", overflow);
        }
        if (leaseDuration.compareTo(minimumLease) < 0) {
            throw new IllegalArgumentException(
                    "app.content.events.lease-duration must cover connection, handshake, RPC, confirm and commit budgets");
        }
        if (pollIntervalMs < 100 || pollIntervalMs > 300_000) {
            throw new IllegalArgumentException("app.content.events.poll-interval-ms must be between 100 and 300000");
        }
    }

    private static void requireDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.content.events." + name + " must be positive");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBackoffBase() {
        return backoffBase;
    }

    public void setBackoffBase(Duration backoffBase) {
        this.backoffBase = backoffBase;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getCommitMargin() {
        return commitMargin;
    }

    public void setCommitMargin(Duration commitMargin) {
        this.commitMargin = commitMargin;
    }

    public int getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(int pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }
}
