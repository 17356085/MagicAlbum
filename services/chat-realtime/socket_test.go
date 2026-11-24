package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/websocket"
)

func TestHealthTracksBrokerAvailability(t *testing.T) {
	clients := &hub{clients: make(map[int64]map[chan struct{}]struct{})}
	handler := realtimeHandler(context.Background(), clients, nil, nil)
	checkStatus := func(want int) {
		t.Helper()
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/health", nil))
		if response.Code != want {
			t.Fatalf("GET /health returned %d, want %d", response.Code, want)
		}
	}
	checkStatus(http.StatusServiceUnavailable)
	clients.setAvailable(true)
	checkStatus(http.StatusOK)
	clients.setAvailable(false)
	checkStatus(http.StatusServiceUnavailable)
}

func TestMetricsTracksRealtimeState(t *testing.T) {
	clients := &hub{clients: make(map[int64]map[chan struct{}]struct{})}
	handler := realtimeHandler(context.Background(), clients, nil, nil)
	checkMetrics := func(wantReady, wantConnections int, wantNotifications uint64) {
		t.Helper()
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/metrics", nil))
		if response.Code != http.StatusOK {
			t.Fatalf("GET /metrics returned %d, want %d", response.Code, http.StatusOK)
		}
		if contentType := response.Header().Get("Content-Type"); contentType != metricsContentType {
			t.Fatalf("GET /metrics content type %q, want %q", contentType, metricsContentType)
		}
		body := response.Body.String()
		for _, want := range []string{
			"# HELP bluealbum_chat_realtime_ready",
			"# TYPE bluealbum_chat_realtime_ready gauge",
			"bluealbum_chat_realtime_ready " + fmt.Sprint(wantReady),
			"# HELP bluealbum_chat_realtime_connections",
			"# TYPE bluealbum_chat_realtime_connections gauge",
			"bluealbum_chat_realtime_connections " + fmt.Sprint(wantConnections),
			"# HELP bluealbum_chat_realtime_notifications_total",
			"# TYPE bluealbum_chat_realtime_notifications_total counter",
			"bluealbum_chat_realtime_notifications_total " + fmt.Sprint(wantNotifications),
		} {
			if !strings.Contains(body, want) {
				t.Fatalf("GET /metrics body missing %q:\n%s", want, body)
			}
		}
	}

	checkMetrics(0, 0, 0)
	clients.setAvailable(true)
	_, unsubscribe := clients.subscribe(7)
	if unsubscribe == nil {
		t.Fatal("subscribe returned no unsubscribe function")
	}
	checkMetrics(1, 1, 0)
	clients.notify([]int64{7})
	checkMetrics(1, 1, 1)
	unsubscribe()
	checkMetrics(1, 0, 1)
	clients.setAvailable(true)
	_, readinessUnsubscribe := clients.subscribe(7)
	if readinessUnsubscribe == nil {
		t.Fatal("subscribe returned no readiness-down cleanup function")
	}
	checkMetrics(1, 1, 1)
	clients.setAvailable(false)
	checkMetrics(0, 0, 1)
	readinessUnsubscribe()
}

func TestRealtimeRoutesChangesToAuthorizedConnections(t *testing.T) {
	core := &http.Client{Transport: responseTransport(func(r *http.Request) (*http.Response, error) {
		var id string
		switch r.Header.Get("Authorization") {
		case "Bearer first-token":
			id = "7"
		case "Bearer second-token":
			id = "42"
		default:
			return &http.Response{StatusCode: http.StatusUnauthorized, Body: http.NoBody}, nil
		}
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(
			`{"userId":` + id + `,"expiresAt":4102444800}`))}, nil
	})}
	clients := &hub{clients: make(map[int64]map[chan struct{}]struct{})}
	clients.setAvailable(true)
	origin := "http://localhost:5173"
	handler := realtimeHandler(context.Background(), clients, map[string]bool{origin: true}, sessionVerifier("http://core.test/session", core))

	request := httptest.NewRequest(http.MethodGet, socketPath, nil)
	request.Header.Set("Origin", "https://untrusted.test")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusForbidden {
		t.Fatalf("untrusted origin returned %d", response.Code)
	}
	rejected := connect(t, handler, origin, "unknown-token")
	var received string
	if err := websocket.Message.Receive(rejected, &received); err == nil {
		t.Fatalf("invalid session received %q", received)
	}

	first := connect(t, handler, origin, "first-token")
	second := connect(t, handler, origin, "second-token")
	expectSignal(t, first, "ready")
	expectSignal(t, second, "ready")
	clients.notify([]int64{7})
	expectSignal(t, first, "changed")
	clients.setAvailable(false)
	if err := websocket.Message.Receive(second, &received); err == nil {
		t.Fatalf("unrelated account received %q", received)
	}

	clients.setAvailable(true)
	reconnected := connect(t, handler, origin, "second-token")
	expectSignal(t, reconnected, "ready")
	clients.notify([]int64{7, 42})
	expectSignal(t, reconnected, "changed")
}

type responseTransport func(*http.Request) (*http.Response, error)

func (transport responseTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	return transport(request)
}

type pipeResponse struct {
	*httptest.ResponseRecorder
	conn net.Conn
	buf  *bufio.ReadWriter
}

func (p pipeResponse) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	return p.conn, p.buf, nil
}

func connect(t *testing.T, handler http.Handler, origin, token string) *websocket.Conn {
	t.Helper()
	client, server := net.Pipe()
	t.Cleanup(func() { _ = client.Close() })
	if err := client.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	go func() {
		defer server.Close()
		buf := bufio.NewReadWriter(bufio.NewReader(server), bufio.NewWriter(server))
		request, err := http.ReadRequest(buf.Reader)
		if err == nil {
			handler.ServeHTTP(pipeResponse{httptest.NewRecorder(), server, buf}, request)
		}
	}()
	config, err := websocket.NewConfig("ws://chat.test"+socketPath, origin)
	if err != nil {
		t.Fatal(err)
	}
	connection, err := websocket.NewClient(config, client)
	if err != nil {
		t.Fatal(err)
	}
	if err := websocket.Message.Send(connection, token); err != nil {
		t.Fatal(err)
	}
	return connection
}

func expectSignal(t *testing.T, connection *websocket.Conn, want string) {
	t.Helper()
	var actual string
	if err := websocket.Message.Receive(connection, &actual); err != nil || actual != want {
		t.Fatalf("received %q, %v, want %q", actual, err, want)
	}
}
