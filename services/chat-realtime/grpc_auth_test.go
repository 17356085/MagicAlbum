package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	authv1 "github.com/17356085/MagicAlbum/services/chat-realtime/internal/authv1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
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

func TestGRPCSessionVerifierAcceptsAdditiveResponseFields(t *testing.T) {
	expiresAt := time.Now().Add(time.Minute).Unix()
	wire, err := proto.Marshal(&authv1.VerifySessionResponse{UserId: 7, ExpiresAt: expiresAt})
	if err != nil {
		t.Fatalf("marshal baseline gRPC response: %v", err)
	}
	// Field 3 is an additive v1 extension that this checkout does not know yet.
	wire = append(wire, 0x18, 0x2a)
	response := &authv1.VerifySessionResponse{}
	if err := proto.Unmarshal(wire, response); err != nil {
		t.Fatalf("unmarshal response with additive field: %v", err)
	}
	if len(response.ProtoReflect().GetUnknown()) == 0 {
		t.Fatal("test response did not retain its additive unknown field")
	}

	server, address := startTestGRPCServerWithOptions(t, 0, response)
	defer server.Stop()
	connection, err := grpc.Dial(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatalf("dial test gRPC server: %v", err)
	}
	defer connection.Close()

	identity, err := grpcSessionVerifier(connection, testGRPCCredential, time.Second)(context.Background(), "grpc-token")
	if err != nil {
		t.Fatalf("gRPC verifier rejected response with additive field: %v", err)
	}
	if identity.UserID != 7 || identity.ExpiresAt != expiresAt {
		t.Fatalf("unexpected identity after additive-field response: %+v", identity)
	}
}

func TestConfiguredSessionVerifierTLSUsesAuthorityAndIdentity(t *testing.T) {
	fixture := newTestTLSFixture(t)
	server, address := startTestGRPCServerWithOptions(t, 0, nil,
		grpc.Creds(credentials.NewTLS(&tls.Config{
			Certificates: []tls.Certificate{fixture.serverCertificate},
			MinVersion:   tls.VersionTLS13,
		})))
	defer server.Stop()

	t.Setenv(grpcAddressEnv, address)
	t.Setenv(grpcCredentialEnv, testGRPCCredential)
	t.Setenv(grpcTimeoutEnv, "1s")
	t.Setenv(grpcTLSCAEnv, fixture.caPath)
	t.Setenv(grpcTLSServerNameEnv, fixture.serverName)
	t.Setenv(grpcTrustedNetworkEnv, "")

	verify, closeVerifier, err := configuredSessionVerifier("http://core.invalid/session", &http.Client{})
	if err != nil {
		t.Fatalf("configured TLS verifier: %v", err)
	}
	defer closeVerifier()
	identity, err := verify(context.Background(), "grpc-token")
	if err != nil {
		t.Fatalf("TLS gRPC verifier call: %v", err)
	}
	if identity.UserID != 7 || identity.ExpiresAt <= time.Now().Unix() {
		t.Fatalf("unexpected TLS gRPC identity: %+v", identity)
	}
}

func TestConfiguredSessionVerifierTLSRejectsIdentityOrAuthorityWithoutHTTPFallback(t *testing.T) {
	cases := []struct {
		name       string
		serverName string
		caPath     func(testTLSFixture, testTLSFixture) string
	}{
		{
			name:       "wrong server name",
			serverName: "wrong.chat-realtime.test",
			caPath:     func(server, _ testTLSFixture) string { return server.caPath },
		},
		{
			name:       "untrusted CA",
			serverName: "chat-realtime.test",
			caPath:     func(_, other testTLSFixture) string { return other.caPath },
		},
	}

	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			serverFixture := newTestTLSFixture(t)
			untrustedFixture := newTestTLSFixture(t)
			server, address := startTestGRPCServerWithOptions(t, 0, nil,
				grpc.Creds(credentials.NewTLS(&tls.Config{
					Certificates: []tls.Certificate{serverFixture.serverCertificate},
					MinVersion:   tls.VersionTLS13,
				})))
			defer server.Stop()

			var fallbackHits atomic.Int32
			httpServer := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
				fallbackHits.Add(1)
				response.WriteHeader(http.StatusInternalServerError)
			}))
			defer httpServer.Close()

			t.Setenv(grpcAddressEnv, address)
			t.Setenv(grpcCredentialEnv, testGRPCCredential)
			t.Setenv(grpcTimeoutEnv, "500ms")
			t.Setenv(grpcTLSCAEnv, testCase.caPath(serverFixture, untrustedFixture))
			t.Setenv(grpcTLSServerNameEnv, testCase.serverName)
			t.Setenv(grpcTrustedNetworkEnv, "")

			verify, closeVerifier, err := configuredSessionVerifier(httpServer.URL, httpServer.Client())
			if err == nil || verify != nil || closeVerifier != nil {
				t.Fatalf("invalid TLS configuration should fail closed: verify=%v close=%v err=%v",
					verify != nil, closeVerifier != nil, err)
			}
			if got := fallbackHits.Load(); got != 0 {
				t.Fatalf("invalid TLS configuration fell back to HTTP %d time(s)", got)
			}
		})
	}
}

func TestConfiguredSessionVerifierTLSHonorsCallerDeadline(t *testing.T) {
	fixture := newTestTLSFixture(t)
	server, address := startTestGRPCServerWithOptions(t, 250*time.Millisecond, nil,
		grpc.Creds(credentials.NewTLS(&tls.Config{
			Certificates: []tls.Certificate{fixture.serverCertificate},
			MinVersion:   tls.VersionTLS13,
		})))
	defer server.Stop()

	var fallbackHits atomic.Int32
	httpServer := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		fallbackHits.Add(1)
		response.WriteHeader(http.StatusInternalServerError)
	}))
	defer httpServer.Close()

	t.Setenv(grpcAddressEnv, address)
	t.Setenv(grpcCredentialEnv, testGRPCCredential)
	t.Setenv(grpcTimeoutEnv, "2s")
	t.Setenv(grpcTLSCAEnv, fixture.caPath)
	t.Setenv(grpcTLSServerNameEnv, fixture.serverName)
	t.Setenv(grpcTrustedNetworkEnv, "")

	verify, closeVerifier, err := configuredSessionVerifier(httpServer.URL, httpServer.Client())
	if err != nil {
		t.Fatalf("configured TLS verifier: %v", err)
	}
	defer closeVerifier()
	callContext, cancel := context.WithTimeout(context.Background(), 25*time.Millisecond)
	defer cancel()
	started := time.Now()
	_, err = verify(callContext, "grpc-token")
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("caller deadline was not preserved: %v", err)
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("caller deadline did not shorten configured timeout: %s", elapsed)
	}
	if got := fallbackHits.Load(); got != 0 {
		t.Fatalf("deadline failure fell back to HTTP %d time(s)", got)
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
	response   *authv1.VerifySessionResponse
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
	response := s.response
	s.mu.Unlock()
	if !valid {
		return nil, status.Error(codes.Unauthenticated, "invalid service credential")
	}
	if response == nil {
		response = &authv1.VerifySessionResponse{UserId: 7, ExpiresAt: time.Now().Add(time.Minute).Unix()}
	}
	return response, nil
}

func startTestGRPCServer(t testing.TB, delay time.Duration) (*grpc.Server, string) {
	return startTestGRPCServerWithOptions(t, delay, nil)
}

func startTestGRPCServerWithOptions(
	t testing.TB,
	delay time.Duration,
	response *authv1.VerifySessionResponse,
	options ...grpc.ServerOption,
) (*grpc.Server, string) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen test gRPC server: %v", err)
	}
	server := grpc.NewServer(options...)
	authv1.RegisterVerifySessionServer(server, &testVerifySessionServer{delay: delay, response: response})
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

type testTLSFixture struct {
	caPath            string
	serverCertificate tls.Certificate
	serverName        string
}

func newTestTLSFixture(t testing.TB) testTLSFixture {
	t.Helper()
	now := time.Now()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate test CA key: %v", err)
	}
	caTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "BlueAlbum gRPC test CA"},
		NotBefore:             now.Add(-time.Minute),
		NotAfter:              now.Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		t.Fatalf("create test CA certificate: %v", err)
	}

	const serverName = "chat-realtime.test"
	serverKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate test server key: %v", err)
	}
	serverTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: serverName},
		NotBefore:    now.Add(-time.Minute),
		NotAfter:     now.Add(time.Hour),
		DNSNames:     []string{serverName},
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	serverDER, err := x509.CreateCertificate(rand.Reader, serverTemplate, caTemplate, &serverKey.PublicKey, caKey)
	if err != nil {
		t.Fatalf("create test server certificate: %v", err)
	}

	serverCertificatePEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: serverDER})
	serverCertificatePEM = append(serverCertificatePEM,
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER})...)
	serverKeyPEM, err := x509.MarshalPKCS8PrivateKey(serverKey)
	if err != nil {
		t.Fatalf("marshal test server key: %v", err)
	}
	serverKeyPEM = pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: serverKeyPEM})
	serverCertificate, err := tls.X509KeyPair(serverCertificatePEM, serverKeyPEM)
	if err != nil {
		t.Fatalf("load test server certificate: %v", err)
	}

	directory := t.TempDir()
	caPath := filepath.Join(directory, "ca.pem")
	if err := os.WriteFile(caPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER}), 0o600); err != nil {
		t.Fatalf("write test CA certificate: %v", err)
	}
	return testTLSFixture{caPath: caPath, serverCertificate: serverCertificate, serverName: serverName}
}
