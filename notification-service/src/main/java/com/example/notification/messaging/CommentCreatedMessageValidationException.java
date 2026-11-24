package com.example.notification.messaging;

/** Does not retain an untrusted envelope body or downstream credentials. */
public final class CommentCreatedMessageValidationException extends RuntimeException {
    public CommentCreatedMessageValidationException() {
        super("INVALID_ENVELOPE");
    }
}
