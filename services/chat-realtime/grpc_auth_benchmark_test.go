package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// BenchmarkSessionVerificationHTTPAndGRPC compares the two opt-in-compatible
// verifier transports with the same identity payload and local server path.
// It intentionally excludes one-time listener and connection setup from both
// measurements; the result is per verification call, not deployment startup.
func BenchmarkSessionVerificationHTTPAndGRPC(b *testing.B) {
	httpServer := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(response).Encode(session{UserID: 7, ExpiresAt: time.Now().Add(time.Minute).Unix()})
	}))
	b.Cleanup(httpServer.Close)
	httpVerify := sessionVerifier(httpServer.URL, httpServer.Client())

	grpcServer, grpcAddress := startTestGRPCServer(b, 0)
	b.Cleanup(grpcServer.Stop)
	grpcConnection, err := grpc.Dial(grpcAddress, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		b.Fatalf("dial benchmark gRPC server: %v", err)
	}
	b.Cleanup(func() { _ = grpcConnection.Close() })
	grpcVerify := grpcSessionVerifier(grpcConnection, testGRPCCredential, time.Second)

	b.Run("HTTP_JSON", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			if _, err := httpVerify(context.Background(), "benchmark-token"); err != nil {
				b.Fatal(err)
			}
		}
	})
	b.Run("gRPC", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			if _, err := grpcVerify(context.Background(), "benchmark-token"); err != nil {
				b.Fatal(err)
			}
		}
	})
}
