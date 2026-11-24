package com.example.notification.messaging;

/** Fixed broker names for the private comment-created RPC contract. */
public final class CommentCreatedMessagingConstants {
    public static final String QUEUE = "notification.comment-created.v1";
    public static final String DEAD_LETTER_EXCHANGE = "notification.comment-created.dlx";
    public static final String DEAD_LETTER_QUEUE = "notification.comment-created.failed.v1";

    private CommentCreatedMessagingConstants() {
    }
}
