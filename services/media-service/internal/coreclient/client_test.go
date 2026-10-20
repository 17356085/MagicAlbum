package coreclient

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/17356085/MagicAlbum/services/media-service/internal/config"
	"github.com/17356085/MagicAlbum/services/media-service/internal/processor"
	"github.com/17356085/MagicAlbum/services/media-service/internal/wire"
)

const (
	testEvent = "01234567-89ab-cdef-0123-456789abcdef"
	testAsset = "11234567-89ab-cdef-0123-456789abcdef"
	testJob   = "21234567-89ab-cdef-0123-456789abcdef"
)

func testEnvelope() wire.Envelope {
	return wire.Envelope{SchemaVersion: 1, EventType: wire.EventType, EventID: testEvent, AssetID: testAsset, JobID: testJob, JobVersion: 1, Preset: wire.PresetPreview, ProcessorVersion: wire.ProcessorVersion, OccurredAt: time.Date(2026, 10, 19, 11, 22, 33, 0, time.UTC)}
}

func testClient(t *testing.T, handler http.Handler, responseTimeout time.Duration) (*Client, func()) {
	t.Helper()
	server := httptest.NewServer(handler)
	base, _ := url.Parse(server.URL + "/api")
	cfg := config.Config{CoreURL: base, InternalServiceToken: "0123456789abcdef0123456789abcdef", HTTPConnectTimeout: time.Second, HTTPResponseTimeout: responseTimeout, MaxJSONBytes: config.MaxJSONBytes, MaxInputBytes: config.MaxInputBytes, MaxOutputBytes: config.MaxOutputBytes}
	client, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	return client, server.Close
}

func TestClaimInputResultAndFailureUseFrozenHTTPContract(t *testing.T) {
	input := []byte("original")
	inputSHA := digest(input)
	var resultSeen atomic.Bool
	client, closeServer := testClient(t, http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Header.Get(HeaderServiceToken) != "0123456789abcdef0123456789abcdef" {
			t.Errorf("missing service token")
		}
		writer.Header().Set("Cache-Control", "no-store")
		switch {
		case strings.HasSuffix(request.URL.Path, "/claim"):
			if request.Method != http.MethodPost || request.URL.RawQuery != "" {
				t.Errorf("claim request shape: %s %s", request.Method, request.URL.String())
			}
			var envelope wire.Envelope
			if err := json.NewDecoder(request.Body).Decode(&envelope); err != nil {
				t.Errorf("claim body: %v", err)
			}
			writer.Header().Set("Content-Type", "application/json")
			json.NewEncoder(writer).Encode(ClaimResponse{Status: "CLAIMED", AssetID: testAsset, JobVersion: 1, Preset: "preview", ProcessorVersion: "1", LeaseToken: "lease-token", LeaseUntil: time.Now().UTC().Add(time.Minute), InputSHA256: inputSHA, ContentType: "image/png", ByteSize: int64(len(input))})
		case strings.HasSuffix(request.URL.Path, "/input"):
			if request.Header.Get(HeaderLeaseToken) != "lease-token" || request.URL.Query().Get("jobVersion") != "1" {
				t.Errorf("input binding missing")
			}
			writer.Header().Set("Content-Type", "image/png")
			_, _ = writer.Write(input)
		case strings.HasSuffix(request.URL.Path, "/result"):
			if request.Header.Get(HeaderLeaseToken) != "lease-token" {
				t.Errorf("result lease missing")
			}
			if err := request.ParseMultipartForm(2 << 20); err != nil {
				t.Errorf("multipart: %v", err)
				return
			}
			if request.FormValue("jobVersion") != "1" || request.FormValue("inputSha256") != inputSHA || request.FormValue("outputSha256") == "" || request.FormValue("processorVersion") != "1" || request.FormValue("width") != "2" || request.FormValue("height") != "1" {
				t.Errorf("wrong result fields: %v", request.MultipartForm.Value)
			}
			file, _, err := request.FormFile("file")
			if err != nil {
				t.Errorf("file: %v", err)
			} else {
				data, _ := io.ReadAll(file)
				resultSeen.Store(bytes.Equal(data, []byte("PNG")))
				_ = file.Close()
			}
			writer.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(writer, `{"status":"SUCCEEDED"}`)
		case strings.HasSuffix(request.URL.Path, "/failure"):
			var body map[string]any
			_ = json.NewDecoder(request.Body).Decode(&body)
			if body["errorCode"] != "MALFORMED_IMAGE" {
				t.Errorf("wrong failure body: %v", body)
			}
			writer.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(writer, `{"status":"FAILED"}`)
		default:
			http.NotFound(writer, request)
		}
	}), time.Second)
	defer closeServer()

	envelope := testEnvelope()
	claim, err := client.Claim(context.Background(), envelope)
	if err != nil {
		if coreErr, ok := err.(*Error); ok {
			t.Fatalf("Claim: %v cause=%#v", err, coreErr.Cause)
		}
		t.Fatalf("Claim: %v", err)
	}
	if claim.Status != "CLAIMED" {
		t.Fatalf("claim status=%q", claim.Status)
	}
	gotInput, err := client.GetInput(context.Background(), testJob, 1, claim.LeaseToken)
	if err != nil || !bytes.Equal(gotInput.Bytes, input) {
		t.Fatalf("GetInput: %+v, %v", gotInput, err)
	}
	resultBytes := []byte("PNG")
	result := processor.Result{Bytes: resultBytes, ContentType: "image/png", Width: 2, Height: 1, SHA256: digest(resultBytes), Preset: processor.PresetPreview, ProcessorVersion: 1}
	if err := client.SubmitResult(context.Background(), testJob, 1, claim.LeaseToken, result, inputSHA); err != nil {
		t.Fatalf("SubmitResult: %v", err)
	}
	if !resultSeen.Load() {
		t.Fatal("result file bytes were not received")
	}
	if err := client.SubmitFailure(context.Background(), testJob, 1, claim.LeaseToken, "MALFORMED_IMAGE"); err != nil {
		t.Fatalf("SubmitFailure: %v", err)
	}
}

func TestClientDoesNotFollowRedirectAndBoundsTimeout(t *testing.T) {
	var redirected atomic.Bool
	client, closeServer := testClient(t, http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path == "/redirected" {
			redirected.Store(true)
			writer.WriteHeader(http.StatusOK)
			return
		}
		writer.Header().Set("Location", "/redirected")
		writer.WriteHeader(http.StatusFound)
	}), time.Second)
	defer closeServer()
	_, err := client.Claim(context.Background(), testEnvelope())
	if err == nil || !IsKind(err, ErrorTransient) {
		t.Fatalf("redirect error=%v", err)
	}
	if redirected.Load() {
		t.Fatal("HTTP client followed redirect")
	}

	timedClient, closeTimed := testClient(t, http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		time.Sleep(150 * time.Millisecond)
		writer.WriteHeader(http.StatusOK)
	}), 30*time.Millisecond)
	defer closeTimed()
	_, err = timedClient.Claim(context.Background(), testEnvelope())
	if err == nil || !IsKind(err, ErrorTransient) {
		t.Fatalf("timeout error=%v", err)
	}
}

func TestClientRetriesResponseLossSafely(t *testing.T) {
	var attempts atomic.Int32
	client, closeServer := testClient(t, http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if !strings.HasSuffix(request.URL.Path, "/failure") {
			http.NotFound(writer, request)
			return
		}
		if attempts.Add(1) == 1 {
			hijacker, ok := writer.(http.Hijacker)
			if !ok {
				t.Fatal("test server lacks hijacker")
			}
			connection, _, err := hijacker.Hijack()
			if err != nil {
				t.Fatal(err)
			}
			_ = connection.Close()
			return
		}
		writer.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(writer, `{"status":"FAILED"}`)
	}), time.Second)
	defer closeServer()
	err := error(nil)
	for attempt := 0; attempt < 2; attempt++ {
		err = client.SubmitFailure(context.Background(), testJob, 1, "lease-token", "TIMEOUT")
		if err == nil {
			break
		}
	}
	if err != nil || attempts.Load() != 2 {
		if coreErr, ok := err.(*Error); ok {
			t.Logf("failure error cause=%#v", coreErr.Cause)
		}
		t.Fatalf("response-loss retry err=%v attempts=%d", err, attempts.Load())
	}
}

func TestResultResponseLossCanBeResolvedByTerminalClaim(t *testing.T) {
	var committed atomic.Bool
	var writes atomic.Int32
	client, closeServer := testClient(t, http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if strings.HasSuffix(request.URL.Path, "/result") {
			writes.Add(1)
			committed.Store(true)
			connection, _, err := writer.(http.Hijacker).Hijack()
			if err != nil {
				t.Error(err)
				return
			}
			_ = connection.Close()
			return
		}
		if strings.HasSuffix(request.URL.Path, "/claim") && committed.Load() {
			_, _ = io.WriteString(writer, `{"status":"SUCCEEDED"}`)
			return
		}
		http.NotFound(writer, request)
	}), time.Second)
	defer closeServer()
	result := processor.Result{Bytes: []byte("result"), ContentType: "image/png", Width: 1, Height: 1, SHA256: digest([]byte("result")), ProcessorVersion: 1}
	if err := client.SubmitResult(context.Background(), testJob, 1, "lease-token", result, digest([]byte("original"))); err == nil {
		t.Fatal("expected lost response")
	}
	claim, err := client.Claim(context.Background(), testEnvelope())
	if err != nil || claim.Status != "SUCCEEDED" || writes.Load() != 1 {
		t.Fatalf("claim=%+v err=%v writes=%d", claim, err, writes.Load())
	}
}

func TestAuthResponsesAreClassifiedWithoutLeakingResponseBody(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusForbidden} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			client, closeServer := testClient(t, http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
				writer.WriteHeader(status)
				_, _ = io.WriteString(writer, `{"error":"secret-token-and-body"}`)
			}), time.Second)
			defer closeServer()

			result := processor.Result{Bytes: []byte("result"), ContentType: "image/png", Width: 1, Height: 1, SHA256: digest([]byte("result")), ProcessorVersion: 1}
			calls := []struct {
				name string
				call func() error
			}{
				{name: "claim", call: func() error { _, err := client.Claim(context.Background(), testEnvelope()); return err }},
				{name: "input", call: func() error { _, err := client.GetInput(context.Background(), testJob, 1, "lease-token"); return err }},
				{name: "renew", call: func() error { _, err := client.Renew(context.Background(), testJob, 1, "lease-token"); return err }},
				{name: "result", call: func() error {
					return client.SubmitResult(context.Background(), testJob, 1, "lease-token", result, digest([]byte("original")))
				}},
				{name: "failure", call: func() error {
					return client.SubmitFailure(context.Background(), testJob, 1, "lease-token", "TIMEOUT")
				}},
			}
			for _, call := range calls {
				t.Run(call.name, func(t *testing.T) {
					err := call.call()
					if err == nil || !IsKind(err, ErrorAuth) {
						t.Fatalf("error=%v", err)
					}
					if strings.Contains(err.Error(), "secret-token") || strings.Contains(err.Error(), "body") {
						t.Fatalf("auth error leaked response data: %v", err)
					}
				})
			}
		})
	}
}

func digest(data []byte) string { sum := sha256.Sum256(data); return hex.EncodeToString(sum[:]) }
