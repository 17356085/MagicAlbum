package com.example.demo.chat.realtimegrpc;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.notifications.client.InternalServiceCredential;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Lifecycle wrapper for the opt-in private gRPC listener.
 *
 * <p>Loopback plaintext is the default pilot transport. Any non-loopback
 * listener must be explicitly enabled and configured with TLS, preventing a
 * deployment typo from exposing an unauthenticated plaintext port.</p>
 */
public final class GrpcRealtimeSessionServer {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Server server;
    private boolean started;

    public GrpcRealtimeSessionServer(
            JwtTokenProvider tokens,
            String bindAddress,
            String credential,
            boolean allowNonLoopback,
            String tlsCertificateChain,
            String tlsPrivateKey) {
        String validatedCredential = InternalServiceCredential.requireStrongConfiguration(credential);
        InetSocketAddress address = parseAddress(bindAddress);
        boolean loopback = isLoopback(address.getHostString());
        String certificate = clean(tlsCertificateChain);
        String privateKey = clean(tlsPrivateKey);
        if (certificate.isEmpty() != privateKey.isEmpty()) {
            throw new IllegalStateException("gRPC TLS certificate and private key must be configured together");
        }
        if (!loopback && !allowNonLoopback) {
            throw new IllegalStateException("gRPC realtime listener must bind to loopback unless explicitly enabled");
        }
        if (!loopback && certificate.isEmpty()) {
            throw new IllegalStateException("non-loopback gRPC realtime listener requires TLS configuration");
        }
        try {
            NettyServerBuilder builder = NettyServerBuilder.forAddress(address)
                    .addService(ServerInterceptors.intercept(
                            new VerifySessionGrpcService(tokens),
                            new GrpcRealtimeCredentialInterceptor(validatedCredential)));
            if (!certificate.isEmpty()) {
                requireReadableFile(certificate, "TLS certificate");
                requireReadableFile(privateKey, "TLS private key");
                builder.useTransportSecurity(Path.of(certificate).toFile(), Path.of(privateKey).toFile());
            }
            this.server = builder.build();
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    public synchronized void start() {
        if (server.isShutdown()) throw new IllegalStateException("gRPC realtime listener is already shut down");
        if (!started) {
            try {
                server.start();
                started = true;
            } catch (IOException exception) {
                throw new IllegalStateException("failed to start gRPC realtime listener", exception);
            }
        }
    }

    public synchronized void stop() {
        if (!server.isShutdown()) {
            server.shutdown();
            try {
                if (!server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
    }

    public int port() {
        return server.getPort();
    }

    static InetSocketAddress parseAddress(String value) {
        String address = clean(value);
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) {
            throw new IllegalStateException("gRPC realtime address must be host:port");
        }
        String host = address.substring(0, separator);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        String portText = address.substring(separator + 1);
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("gRPC realtime address has an invalid port");
        }
        if (port < 1 || port > 65535 || host.isBlank() || host.contains("/")) {
            throw new IllegalStateException("gRPC realtime address is invalid");
        }
        return new InetSocketAddress(host, port);
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException exception) {
            throw new IllegalStateException("gRPC realtime bind host cannot be resolved");
        }
    }

    private static void requireReadableFile(String value, String description) {
        Path path = Path.of(value);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException(description + " is not a readable file");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
