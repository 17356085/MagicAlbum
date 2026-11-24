package main

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
	"time"

	authv1 "github.com/17356085/MagicAlbum/services/chat-realtime/internal/authv1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

const testGRPCCredential = "grpc-test-credential-1234567890-abcdefgh"

func TestConfiguredSessionVerifierDefaultsToHTTPJSON(t *testing.T) {
	t.Setenv(grpcAddressEnv, "")
	t.Setenv(grpcCredentialEnv, "")
	var authorization string
	httpServer := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		authorization = request.Header.Get("Authorization")
		response.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(response).Encode(session{UserID: 7, ExpiresAt: time.Now().Add(time.Minute).Unix()})
	}))
	defer httpServer.Close()

	verify, closeVerifier, err := configuredSessionVerifier(httpServer.URL, httpServer.Client())
	if err != nil {
		t.Fatalf("default verifier configuration: %v", err)
	}
	defer closeVerifier()
	identity, err := verify(context.Background(), "http-token")
	if err != nil {
		t.Fatalf("default verifier call: %v", err)
	}
	if identity.UserID != 7 || authorization != "Bearer http-token" {
		t.Fatalf("default HTTP verifier identity=%+v authorization=%q", identity, authorization)
	}
}

func TestConfiguredSessionVerifierDoesNotDowngradeWhenGRPCUnavailable(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("allocate unavailable gRPC address: %v", err)
	}
	address := listener.Addr().String()
	_ = listener.Close()
	t.Setenv(grpcAddressEnv, address)
	t.Setenv(grpcCredentialEnv, testGRPCCredential)
	t.Setenv(grpcTimeoutEnv, "100ms")

	verify, closeVerifier, err := configuredSessionVerifier("http://core.invalid/session", &http.Client{})
	if err == nil || verify != nil || closeVerifier != nil {
		t.Fatalf("unavailable opted-in gRPC verifier should fail without HTTP downgrade: verify=%v close=%v err=%v",
			verify != nil, closeVerifier != nil, err)
	}
}

func TestGRPCSessionVerifierSendsIndependentCredential(t *testing.T) {
	server, address := startTestGRPCServer(t, 0)
	defer server.Stop()

	connection, err := grpc.Dial(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatalf("dial test gRPC server: %v", err)
	}
	defer connection.Close()
	verify := grpcSessionVerifier(connection, testGRPCCredential, time.Second)
	identity, err := verify(context.Background(), "grpc-token")
	if err != nil {
		t.Fatalf("gRPC verifier call: %v", err)
	}
	if identity.UserID != 7 || identity.ExpiresAt <= time.Now().Unix() {
		t.Fatalf("unexpected gRPC identity: %+v", identity)
	}

	wrongCredential := grpcSessionVerifier(connection, "wrong-credential-1234567890-abcdefgh", time.Second)
	if _, err := wrongCredential(context.Background(), "grpc-token"); !errors.Is(err, errInvalidGRPCSession) {
		t.Fatalf("wrong service credential error=%v, want invalid session", err)
	}
}

func TestGRPCSessionVerifierHonorsBoundedTimeout(t *testing.T) {
	server, address := startTestGRPCServer(t, 250*time.Millisecond)
	defer server.Stop()
	connection, err := grpc.Dial(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatalf("dial test gRPC server: %v", err)
	}
	defer connection.Close()

	started := time.Now()
	_, err = grpcSessionVerifier(connection, testGRPCCredential, 25*time.Millisecond)(context.Background(), "grpc-token")
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("bounded gRPC verifier error=%v, want deadline exceeded", err)
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("bounded gRPC verifier took %s", elapsed)
	}
}

func TestGRPCSessionVerifierJavaInterop(t *testing.T) {
	address := os.Getenv("CHAT_REALTIME_JAVA_GRPC_ADDR")
	token := os.Getenv("CHAT_REALTIME_JAVA_GRPC_TOKEN")
	credential := os.Getenv(grpcCredentialEnv)
	if address == "" || token == "" || credential == "" {
		t.Skip("set CHAT_REALTIME_JAVA_GRPC_ADDR, CHAT_REALTIME_JAVA_GRPC_TOKEN, and CHAT_REALTIME_GRPC_CREDENTIAL for Java interop")
	}
	connection, err := grpc.Dial(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatalf("dial Java gRPC session listener: %v", err)
	}
	defer connection.Close()
	identity, err := grpcSessionVerifier(connection, credential, 3*time.Second)(context.Background(), token)
	if err != nil {
		t.Fatalf("Java gRPC session verification: %v", err)
	}
	if identity.UserID <= 0 || identity.ExpiresAt <= time.Now().Unix() {
		t.Fatalf("Java gRPC returned invalid session: %+v", identity)
	}
}

func TestGRPCCredentialValidation(t *testing.T) {
	for _, value := range []string{"", "short", "replace-me-012345678901234567890123", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"} {
		if err := validateGRPCCredential(value); err == nil {
			t.Errorf("validateGRPCCredential(%q) accepted an unsafe value", value)
		}
	}
	if err := validateGRPCCredential(testGRPCCredential); err != nil {
		t.Fatalf("test credential rejected: %v", err)
	}
}

type testVerifySessionServer struct {
	authv1.UnimplementedVerifySessionServer
	delay      time.Duration
	credential []string
	mu         sync.Mutex
}

func (s *testVerifySessionServer) VerifySession(ctx context.Context, _ *authv1.VerifySessionRequest) (*authv1.VerifySessionResponse, error) {
	var credential []string
	if headers, ok := metadata.FromIncomingContext(ctx); ok {
		credential = headers.Get(grpcCredentialHeader)
	}
	s.mu.Lock()
	s.credential = append([]string(nil), credential...)
	s.mu.Unlock()
	if s.delay > 0 {
		select {
		case <-time.After(s.delay):
		case <-ctx.Done():
			return nil, status.FromContextError(ctx.Err()).Err()
		}
	}
	s.mu.Lock()
	valid := len(s.credential) == 1 && s.credential[0] == testGRPCCredential
	s.mu.Unlock()
	if !valid {
		return nil, status.Error(codes.Unauthenticated, "invalid service credential")
	}
	return &authv1.VerifySessionResponse{UserId: 7, ExpiresAt: time.Now().Add(time.Minute).Unix()}, nil
}

func startTestGRPCServer(t testing.TB, delay time.Duration) (*grpc.Server, string) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen test gRPC server: %v", err)
	}
	server := grpc.NewServer()
	authv1.RegisterVerifySessionServer(server, &testVerifySessionServer{delay: delay})
	go func() {
		if serveErr := server.Serve(listener); serveErr != nil && !errors.Is(serveErr, grpc.ErrServerStopped) {
			t.Errorf("test gRPC server: %v", serveErr)
		}
	}()
	t.Cleanup(func() {
		server.Stop()
		_ = listener.Close()
	})
	return server, listener.Addr().String()
}
