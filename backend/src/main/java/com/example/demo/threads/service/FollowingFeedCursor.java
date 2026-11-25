package com.example.demo.threads.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;

record FollowingFeedCursor(Instant createdAt, long threadId) {
    private static final int VERSION = 1;
    private static final int LENGTH = 1 + 3 * Long.BYTES + Integer.BYTES;
    private static final int ENCODED_LENGTH = (LENGTH * 4 + 2) / 3;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    String encode(long userId) {
        byte[] bytes = ByteBuffer.allocate(LENGTH)
                .put((byte) VERSION).putLong(userId)
                .putLong(createdAt.getEpochSecond()).putInt(createdAt.getNano()).putLong(threadId)
                .array();
        return ENCODER.encodeToString(bytes);
    }

    static FollowingFeedCursor decode(String token, long userId) {
        if (token.length() != ENCODED_LENGTH) {
            throw invalid();
        }
        byte[] bytes;
        try {
            bytes = DECODER.decode(token);
        } catch (IllegalArgumentException badEncoding) {
            throw invalid();
        }
        if (bytes.length != LENGTH || !ENCODER.encodeToString(bytes).equals(token)) {
            throw invalid();
        }
        ByteBuffer fields = ByteBuffer.wrap(bytes);
        if (fields.get() != VERSION || fields.getLong() != userId) {
            throw invalid();
        }
        long epochSecond = fields.getLong();
        int nanos = fields.getInt();
        long threadId = fields.getLong();
        // The production created_at column is a MySQL TIMESTAMP.
        if (epochSecond < 0 || epochSecond > Integer.MAX_VALUE || nanos < 0 || nanos >= 1_000_000_000
                || threadId <= 0) {
            throw invalid();
        }
        return new FollowingFeedCursor(Instant.ofEpochSecond(epochSecond, nanos), threadId);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的关注流游标");
    }
}
