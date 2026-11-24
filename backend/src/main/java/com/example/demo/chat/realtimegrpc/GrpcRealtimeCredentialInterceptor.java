package com.example.demo.chat.realtimegrpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Authenticates the private gRPC hop independently from the end-user JWT.
 *
 * <p>The credential is intentionally carried as metadata rather than in the
 * protobuf request. This keeps a copied/replayed JWT from being sufficient to
 * reach the pilot listener and lets operators rotate the service credential
 * independently.</p>
 */
public final class GrpcRealtimeCredentialInterceptor implements ServerInterceptor {
    public static final String CREDENTIAL_METADATA_NAME = "x-bluealbum-grpc-credential";

    private static final Metadata.Key<String> CREDENTIAL_METADATA = Metadata.Key.of(
            CREDENTIAL_METADATA_NAME, Metadata.ASCII_STRING_MARSHALLER);

    private final byte[] expectedCredential;

    public GrpcRealtimeCredentialInterceptor(String credential) {
        this.expectedCredential = credential.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
            ServerCall<RequestT, ResponseT> call,
            Metadata headers,
            ServerCallHandler<RequestT, ResponseT> next) {
        List<String> credentials = values(headers);
        if (credentials.size() != 1 || !matches(credentials.getFirst())) {
            call.close(Status.UNAUTHENTICATED.withDescription("invalid service credential"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }

    private static List<String> values(Metadata headers) {
        Iterable<String> all = headers.getAll(CREDENTIAL_METADATA);
        if (all == null) return List.of();
        List<String> result = new ArrayList<>(2);
        for (String value : all) {
            if (value == null || result.size() == 2) return List.of("duplicate");
            result.add(value);
        }
        return List.copyOf(result);
    }

    private boolean matches(String candidate) {
        if (candidate == null) return false;
        byte[] supplied = candidate.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expectedCredential, supplied);
    }
}
