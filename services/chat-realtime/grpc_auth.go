package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	authv1 "github.com/17356085/MagicAlbum/services/chat-realtime/internal/authv1"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

const (
	grpcAddressEnv        = "CHAT_REALTIME_GRPC_ADDR"
	grpcCredentialEnv     = "CHAT_REALTIME_GRPC_CREDENTIAL"
	grpcTimeoutEnv        = "CHAT_REALTIME_GRPC_TIMEOUT"
	grpcTLSCAEnv          = "CHAT_REALTIME_GRPC_TLS_CA"
	grpcTLSServerNameEnv  = "CHAT_REALTIME_GRPC_TLS_SERVER_NAME"
	grpcTrustedNetworkEnv = "CHAT_REALTIME_GRPC_TRUSTED_NETWORK"
	grpcCredentialHeader  = "x-bluealbum-grpc-credential"
	defaultGRPCTimeout    = 3 * time.Second
	maxGRPCTimeout        = 10 * time.Second
)

var (
	errInvalidGRPCSession = errors.New("chat session rejected")
	errGRPCUnavailable    = errors.New("chat session verifier unavailable")
)

// configuredSessionVerifier keeps the legacy HTTP/JSON verifier as the exact
// default. Setting either gRPC setting without the other is a startup error;
// a failed opted-in gRPC dial is never silently downgraded to HTTP.
func configuredSessionVerifier(coreEndpoint string, client *http.Client) (verifier, func() error, error) {
	address := strings.TrimSpace(os.Getenv(grpcAddressEnv))
	credential := strings.TrimSpace(os.Getenv(grpcCredentialEnv))
	if address == "" {
		if credential != "" {
			return nil, nil, errors.New("CHAT_REALTIME_GRPC_CREDENTIAL requires CHAT_REALTIME_GRPC_ADDR")
		}
		return sessionVerifier(coreEndpoint, client), func() error { return nil }, nil
	}
	if err := validateGRPCCredential(credential); err != nil {
		return nil, nil, err
	}
	timeout, err := grpcTimeout()
	if err != nil {
		return nil, nil, err
	}
	transport, err := grpcTransportCredentials(address)
	if err != nil {
		return nil, nil, err
	}
	dialContext, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	connection, err := grpc.DialContext(dialContext, address,
		grpc.WithTransportCredentials(transport), grpc.WithBlock(),
		grpc.WithDefaultCallOptions(grpc.MaxCallRecvMsgSize(1024)))
	if err != nil {
		return nil, nil, errors.New("chat realtime gRPC verifier unavailable")
	}
	return grpcSessionVerifier(connection, credential, timeout), connection.Close, nil
}

func grpcSessionVerifier(connection grpc.ClientConnInterface, credential string, timeout time.Duration) verifier {
	client := authv1.NewVerifySessionClient(connection)
	return func(ctx context.Context, token string) (session, error) {
		if strings.TrimSpace(token) == "" {
			return session{}, errInvalidGRPCSession
		}
		callContext, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		callContext, span := tracerForContext(callContext).Start(callContext, "chat.session.verify",
			trace.WithSpanKind(trace.SpanKindClient))
		defer span.End()
		callContext = metadata.AppendToOutgoingContext(callContext, grpcCredentialHeader, credential)
		if span.SpanContext().IsValid() {
			carrier := propagation.MapCarrier{}
			traceContextPropagator.Inject(callContext, carrier)
			for _, header := range carrier.Keys() {
				callContext = metadata.AppendToOutgoingContext(callContext, header, carrier.Get(header))
			}
		}
		response, err := client.VerifySession(callContext, &authv1.VerifySessionRequest{Token: token})
		if err != nil {
			switch status.Code(err) {
			case codes.Unauthenticated, codes.InvalidArgument:
				return session{}, errInvalidGRPCSession
			case codes.DeadlineExceeded:
				return session{}, context.DeadlineExceeded
			default:
				return session{}, errGRPCUnavailable
			}
		}
		if response == nil || response.GetUserId() <= 0 ||
			response.GetExpiresAt() <= time.Now().Unix() {
			return session{}, errInvalidGRPCSession
		}
		return session{UserID: response.GetUserId(), ExpiresAt: response.GetExpiresAt()}, nil
	}
}

func grpcTimeout() (time.Duration, error) {
	value := strings.TrimSpace(os.Getenv(grpcTimeoutEnv))
	if value == "" {
		return defaultGRPCTimeout, nil
	}
	timeout, err := time.ParseDuration(value)
	if err != nil || timeout <= 0 || timeout > maxGRPCTimeout {
		return 0, fmt.Errorf("%s must be a duration between 1ms and %s", grpcTimeoutEnv, maxGRPCTimeout)
	}
	return timeout, nil
}

func validateGRPCCredential(credential string) error {
	if len(credential) < 32 || len(credential) > 256 {
		return errors.New("CHAT_REALTIME_GRPC_CREDENTIAL must contain 32-256 printable characters")
	}
	distinct := make(map[byte]struct{}, len(credential))
	lower := strings.ToLower(credential)
	for index := 0; index < len(credential); index++ {
		if credential[index] < 33 || credential[index] > 126 {
			return errors.New("CHAT_REALTIME_GRPC_CREDENTIAL must contain printable ASCII characters")
		}
		distinct[credential[index]] = struct{}{}
	}
	for _, marker := range []string{"change-me", "change_me", "replace-me", "replace_me", "replace-with", "replace_with", "placeholder", "devsecret"} {
		if strings.Contains(lower, marker) {
			return errors.New("CHAT_REALTIME_GRPC_CREDENTIAL must not be a placeholder")
		}
	}
	if len(distinct) < 8 {
		return errors.New("CHAT_REALTIME_GRPC_CREDENTIAL must contain varied random characters")
	}
	return nil
}

func grpcTransportCredentials(address string) (credentials.TransportCredentials, error) {
	host, _, err := net.SplitHostPort(address)
	if err != nil || host == "" {
		return nil, errors.New("CHAT_REALTIME_GRPC_ADDR must be host:port")
	}
	caPath := strings.TrimSpace(os.Getenv(grpcTLSCAEnv))
	if caPath != "" {
		pem, readErr := os.ReadFile(caPath)
		if readErr != nil {
			return nil, errors.New("CHAT_REALTIME_GRPC_TLS_CA is unreadable")
		}
		roots := x509.NewCertPool()
		if !roots.AppendCertsFromPEM(pem) {
			return nil, errors.New("CHAT_REALTIME_GRPC_TLS_CA does not contain a certificate")
		}
		serverName := strings.TrimSpace(os.Getenv(grpcTLSServerNameEnv))
		if serverName == "" {
			serverName = host
		}
		return credentials.NewTLS(&tls.Config{
			MinVersion: tls.VersionTLS13,
			RootCAs:    roots,
			ServerName: serverName,
		}), nil
	}
	loopbackOrPrivate, privateErr := privateHost(host)
	if privateErr != nil {
		return nil, errors.New("CHAT_REALTIME_GRPC_ADDR must resolve to a private host")
	}
	trustedNetwork, parseErr := strconv.ParseBool(strings.TrimSpace(os.Getenv(grpcTrustedNetworkEnv)))
	if parseErr != nil && strings.TrimSpace(os.Getenv(grpcTrustedNetworkEnv)) != "" {
		return nil, errors.New("CHAT_REALTIME_GRPC_TRUSTED_NETWORK must be true or false")
	}
	if !loopbackOrPrivate || (!isLoopbackHost(host) && !trustedNetwork) {
		return nil, errors.New("gRPC plaintext requires loopback or explicit trusted-network configuration")
	}
	return insecure.NewCredentials(), nil
}

func privateHost(host string) (bool, error) {
	addresses, err := net.LookupIP(host)
	if err != nil || len(addresses) == 0 {
		return false, err
	}
	for _, address := range addresses {
		if !(address.IsLoopback() || address.IsPrivate() || address.IsLinkLocalUnicast()) {
			return false, nil
		}
	}
	return true, nil
}

func isLoopbackHost(host string) bool {
	addresses, err := net.LookupIP(host)
	if err != nil {
		return false
	}
	for _, address := range addresses {
		if address.IsLoopback() {
			return true
		}
	}
	return false
}
