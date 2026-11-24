package com.example.demo.notifications.outbox;

import com.example.demo.notifications.dto.NotificationDto;
import com.example.demo.notifications.service.NotificationService;
import com.example.demo.notifications.service.NotificationCreationAcknowledgement;
import com.example.demo.notifications.service.rabbit.CommentCreatedEnvelope;
import com.example.demo.notifications.service.rabbit.CommentCreatedRabbitTransport;
import com.example.demo.user.service.UserSettingsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.notifications.client.mode", havingValue = "remote", matchIfMissing = true)
public class NotificationOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxDispatcher.class);

    private final NotificationOutboxService outboxService;
    private final NotificationService notificationService;
    private final UserSettingsService userSettingsService;
    private final CommentCreatedRabbitTransport commentCreatedTransport;

    @Autowired
    NotificationOutboxDispatcher(
            NotificationOutboxService outboxService,
            NotificationService notificationService,
            UserSettingsService userSettingsService,
            ObjectProvider<CommentCreatedRabbitTransport> commentCreatedTransportProvider
    ) {
        this(outboxService, notificationService, userSettingsService,
                commentCreatedTransportProvider == null ? null : commentCreatedTransportProvider.getIfAvailable());
    }

    /** Preserves the direct/HTTP constructor used by existing tests and callers. */
    public NotificationOutboxDispatcher(
            NotificationOutboxService outboxService,
            NotificationService notificationService,
            UserSettingsService userSettingsService
    ) {
        this(outboxService, notificationService, userSettingsService,
                (CommentCreatedRabbitTransport) null);
    }

    /** Isolated constructor for the opt-in Rabbit delivery contract. */
    public NotificationOutboxDispatcher(
            NotificationOutboxService outboxService,
            NotificationService notificationService,
            UserSettingsService userSettingsService,
            CommentCreatedRabbitTransport commentCreatedTransport
    ) {
        this.outboxService = outboxService;
        this.notificationService = notificationService;
        this.userSettingsService = userSettingsService;
        this.commentCreatedTransport = commentCreatedTransport;
    }

    public void dispatch() {
        int delivered = 0;
        for (int index = 0; index < outboxService.batchSize(); index++) {
            // Claim immediately before delivery so waiting rows do not consume their lease.
            String owner = UUID.randomUUID().toString();
            Optional<NotificationOutboxService.Delivery> next = outboxService.claimOne(owner);
            if (next.isEmpty()) break;
            deliver(owner, next.get());
            delivered++;
        }
        log.debug("notification_outbox_claimed count={}", delivered);
        outboxService.cleanupTerminal();
    }

    private void deliver(String owner, NotificationOutboxService.Delivery delivery) {
        String previousRequestId = MDC.get("requestId");
        String requestId = validRequestId(delivery.requestId())
                ? delivery.requestId()
                : validRequestId(delivery.eventId()) ? delivery.eventId() : null;
        if (requestId == null) {
            MDC.remove("requestId");
        } else {
            MDC.put("requestId", requestId);
        }
        try {
            if (!userSettingsService.shouldDeliver(delivery.recipientUserId(), delivery.type())) {
                outboxService.markSuppressed(delivery.id(), owner, "preference disabled before delivery");
                return;
            }
            var response = deliverToSelectedTransport(delivery);
            NotificationCreationAcknowledgement.requireCreated(response, delivery.type(), delivery.title(), delivery.content(),
                    delivery.threadId(), delivery.targetId(), delivery.targetType());
            outboxService.markDelivered(delivery.id(), owner);
        } catch (Exception failure) {
            boolean updated = outboxService.markFailedOrRetry(delivery, owner, failure);
            if (!updated) {
                log.warn("notification_outbox_stale_lease id={}", delivery.id());
            } else {
                log.warn("notification_outbox_delivery_failed id={} attempt={} error={}",
                        delivery.id(), delivery.attempts(), failure.getClass().getSimpleName());
            }
        } finally {
            if (previousRequestId == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousRequestId);
            }
        }
    }

    private NotificationDto deliverToSelectedTransport(
            NotificationOutboxService.Delivery delivery
    ) {
        if (commentCreatedTransport != null && CommentCreatedEnvelope.supports(delivery)) {
            // The Rabbit path is selected only for the existing comment reply
            // intent. Other notification intents retain the HTTP contract.
            return commentCreatedTransport.deliver(delivery);
        }
        return notificationService.create(
                delivery.recipientUserId(),
                delivery.type(),
                delivery.title(),
                delivery.content(),
                delivery.threadId(),
                delivery.targetId(),
                delivery.targetType(),
                UUID.fromString(delivery.eventId())
        );
    }

    private boolean validRequestId(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.matches("[A-Za-z0-9._:-]+");
    }
}
