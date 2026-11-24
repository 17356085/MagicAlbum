package com.example.demo.chat.realtimegrpc;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.JwtTokenProvider.Session;
import com.example.demo.chat.realtimegrpc.contract.VerifySessionGrpc;
import com.example.demo.chat.realtimegrpc.contract.VerifySessionRequest;
import com.example.demo.chat.realtimegrpc.contract.VerifySessionResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;

/** Implements the versioned chat realtime session-authentication contract. */
public final class VerifySessionGrpcService extends VerifySessionGrpc.VerifySessionImplBase {
    private static final Status INVALID_SESSION = Status.UNAUTHENTICATED.withDescription("invalid session");

    private final JwtTokenProvider tokens;

    public VerifySessionGrpcService(JwtTokenProvider tokens) {
        this.tokens = tokens;
    }

    @Override
    public void verifySession(VerifySessionRequest request, StreamObserver<VerifySessionResponse> observer) {
        if (request == null || request.getToken().isBlank()) {
            observer.onError(INVALID_SESSION.asRuntimeException());
            return;
        }
        Session session;
        try {
            // Keep issuer, signature and expiry semantics in one place with
            // the existing HTTP session endpoint.
            session = tokens.getSessionFromToken(request.getToken());
        } catch (RuntimeException ignored) {
            session = null;
        }
        if (session == null || session.userId() <= 0
                || session.expiresAt() <= Instant.now().getEpochSecond()) {
            observer.onError(INVALID_SESSION.asRuntimeException());
            return;
        }
        observer.onNext(VerifySessionResponse.newBuilder()
                .setUserId(session.userId())
                .setExpiresAt(session.expiresAt())
                .build());
        observer.onCompleted();
    }
}
