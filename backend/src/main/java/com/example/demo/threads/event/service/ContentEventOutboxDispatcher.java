package com.example.demo.threads.event.service;

import com.example.demo.threads.event.port.ContentEventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Serially publishes claimed rows so a batch does not exhaust leases in parallel. */
@Component
@ConditionalOnProperty(name = "app.content.events.enabled", havingValue = "true")
public class ContentEventOutboxDispatcher {
    private final ContentEventOutboxService outbox;
    private final ContentEventPublisher publisher;

    public ContentEventOutboxDispatcher(ContentEventOutboxService outbox,
                                        @Qualifier("contentEventPublisherTransport") ContentEventPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
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
                publisher.publish(claimed.message());
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
