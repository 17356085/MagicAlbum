package com.example.demo.threads.event.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Serially publishes claimed rows so a batch does not exhaust leases in parallel. */
@Component
@ConditionalOnProperty(name = "app.content.events.enabled", havingValue = "true")
public class ContentEventOutboxDispatcher {
    private final ContentEventOutboxService outbox;
    private final ContentEventPublisherTransport transport;

    public ContentEventOutboxDispatcher(ContentEventOutboxService outbox,
                                        ContentEventPublisherTransport transport) {
        this.outbox = outbox;
        this.transport = transport;
    }

    public void dispatchOnce() {
        for (int index = 0; index < outbox.batchSize(); index++) {
            String owner = UUID.randomUUID().toString();
            var delivery = outbox.claimOne(owner);
            if (delivery.isEmpty()) {
                return;
            }
            var claimed = delivery.get();
            try {
                transport.publish(claimed.message());
                outbox.markPublished(claimed);
            } catch (RuntimeException failure) {
                outbox.markFailure(claimed, failure);
            }
        }
    }

    /** Alias for explicit admin or maintenance callers. */
    public void dispatch() {
        dispatchOnce();
    }
}
