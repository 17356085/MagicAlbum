package com.example.demo.chat.realtimegrpc;

import com.example.demo.auth.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in configuration for the private gRPC session-authentication pilot. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.chat.realtime.grpc", name = "enabled", havingValue = "true")
public class GrpcRealtimeSessionConfiguration {
    @Bean(initMethod = "start", destroyMethod = "stop")
    GrpcRealtimeSessionServer grpcRealtimeSessionServer(
            JwtTokenProvider tokens,
            @Value("${app.chat.realtime.grpc.address:127.0.0.1:9092}") String address,
            @Value("${app.chat.realtime.grpc.credential:${CHAT_REALTIME_GRPC_CREDENTIAL:}}") String credential,
            @Value("${app.chat.realtime.grpc.allow-non-loopback:false}") boolean allowNonLoopback,
            @Value("${app.chat.realtime.grpc.tls.certificate-chain:}") String tlsCertificateChain,
            @Value("${app.chat.realtime.grpc.tls.private-key:}") String tlsPrivateKey) {
        return new GrpcRealtimeSessionServer(tokens, address, credential, allowNonLoopback,
                tlsCertificateChain, tlsPrivateKey);
    }
}
