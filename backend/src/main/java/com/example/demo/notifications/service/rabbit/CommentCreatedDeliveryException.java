package com.example.demo.notifications.service.rabbit;

/** Safe failure categories for the comment-created RPC transport. */
public final class CommentCreatedDeliveryException extends RuntimeException {
    public enum Code {
        INVALID_INTENT,
        SERIALIZATION,
        TIMEOUT,
        TRANSPORT,
        INVALID_ACK
    }

    private final Code code;

    public CommentCreatedDeliveryException(Code code) {
        super(code.name());
        this.code = code;
    }

    public CommentCreatedDeliveryException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
