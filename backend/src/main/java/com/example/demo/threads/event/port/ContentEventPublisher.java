package com.example.demo.threads.event.port;

import com.example.demo.threads.event.dto.ContentEventMessage;

/** Publishes an already committed content event to its configured destination. */
public interface ContentEventPublisher {
    /**
     * Returns only after the destination accepts the event. A confirmation
     * timeout or transport error may leave the outcome unknown. Retries must
     * use the original event ID.
     *
     * @throws PublishException if publication cannot be confirmed
     */
    void publish(ContentEventMessage event);

    /** Stable diagnostic codes stored in the outbox and exposed to operators. */
    enum Code {
        SERIALIZATION,
        CONFIRM_NACK,
        CONFIRM_TIMEOUT,
        RETURNED,
        TRANSPORT
    }

    final class PublishException extends RuntimeException {
        private final Code code;

        public PublishException(Code code) {
            super(code == null ? Code.TRANSPORT.name() : code.name());
            this.code = code == null ? Code.TRANSPORT : code;
        }

        public Code code() {
            return code;
        }
    }
}
