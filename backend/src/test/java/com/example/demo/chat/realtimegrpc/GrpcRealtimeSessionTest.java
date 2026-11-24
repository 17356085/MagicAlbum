package com.example.demo.chat.realtimegrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.chat.realtimegrpc.contract.VerifySessionGrpc;
import com.example.demo.chat.realtimegrpc.contract.VerifySessionRequest;
import com.example.demo.chat.realtimegrpc.contract.VerifySessionResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GrpcRealtimeSessionTest {
    private static final String CREDENTIAL = "grpc-test-credential-1234567890-abcdefgh";
    private static final Metadata.Key<String> CREDENTIAL_METADATA = Metadata.Key.of(
            GrpcRealtimeCredentialInterceptor.CREDENTIAL_METADATA_NAME,
            Metadata.ASCII_STRING_MARSHALLER);

    @Test
    void verifiesJwtSessionOnlyWithIndependentServiceCredential() throws Exception {
        JwtTokenProvider tokens = configuredTokens();
        String token = tokens.generateToken(42L, "pilot-user");
        int port = freePort();
        GrpcRealtimeSessionServer server = new GrpcRealtimeSessionServer(
                tokens, "127.0.0.1:" + port, CREDENTIAL, false, "", "");
        server.start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port)
                .usePlaintext()
                .build();
        try {
            VerifySessionGrpc.VerifySessionBlockingStub authenticated = stub(channel, CREDENTIAL);
            VerifySessionResponse response = authenticated.verifySession(
                    VerifySessionRequest.newBuilder().setToken(token).build());
            assertEquals(42L, response.getUserId());
            assertEquals(tokens.getSessionFromToken(token).expiresAt(), response.getExpiresAt());

            StatusRuntimeException missingCredential = assertThrows(StatusRuntimeException.class,
                    () -> stub(channel, null).verifySession(request(token)));
            assertEquals(Status.Code.UNAUTHENTICATED, missingCredential.getStatus().getCode());

            StatusRuntimeException wrongCredential = assertThrows(StatusRuntimeException.class,
                    () -> stub(channel, "wrong-credential-1234567890-abcdefgh")
                            .verifySession(request(token)));
            assertEquals(Status.Code.UNAUTHENTICATED, wrongCredential.getStatus().getCode());

            StatusRuntimeException invalidToken = assertThrows(StatusRuntimeException.class,
                    () -> authenticated.verifySession(request("not-a-jwt")));
            assertEquals(Status.Code.UNAUTHENTICATED, invalidToken.getStatus().getCode());
        } finally {
            channel.shutdownNow();
            server.stop();
        }
    }

    @Test
    void rejectsUnsafeListenerConfiguration() {
        JwtTokenProvider tokens = configuredTokens();
        assertThrows(IllegalStateException.class, () -> new GrpcRealtimeSessionServer(
                tokens, "0.0.0.0:9092", CREDENTIAL, false, "", ""));
        assertThrows(IllegalStateException.class, () -> new GrpcRealtimeSessionServer(
                tokens, "127.0.0.1:9092", "short", false, "", ""));
    }

    private static VerifySessionRequest request(String token) {
        return VerifySessionRequest.newBuilder().setToken(token).build();
    }

    private static VerifySessionGrpc.VerifySessionBlockingStub stub(ManagedChannel channel, String credential) {
        if (credential == null) return VerifySessionGrpc.newBlockingStub(channel);
        Metadata metadata = new Metadata();
        metadata.put(CREDENTIAL_METADATA, credential);
        return VerifySessionGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static JwtTokenProvider configuredTokens() {
        JwtTokenProvider tokens = new JwtTokenProvider();
        ReflectionTestUtils.setField(tokens, "secret",
                "grpc-test-jwt-secret-0123456789-abcdefghijklmnopqrstuvwxyz");
        ReflectionTestUtils.setField(tokens, "issuer", "MagicAlbum");
        ReflectionTestUtils.setField(tokens, "ttlMinutes", 60L);
        tokens.validateConfiguration();
        assertNotNull(tokens.getSessionFromToken(tokens.generateToken(1L, "test")));
        return tokens;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
