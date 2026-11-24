package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
	"golang.org/x/net/websocket"
)

const socketPath = "/api/v1/chat/realtime"

const metricsContentType = "text/plain; version=0.0.4"

type session struct {
	UserID    int64 `json:"userId"`
	ExpiresAt int64 `json:"expiresAt"`
}

type verifier func(context.Context, string) (session, error)

func sessionVerifier(endpoint string, client *http.Client) verifier {
	return func(ctx context.Context, token string) (session, error) {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
		if err != nil {
			return session{}, err
		}
		ctx, span := tracerForContext(ctx).Start(ctx, "chat.session.verify", trace.WithSpanKind(trace.SpanKindClient))
		defer span.End()
		request = request.WithContext(ctx)
		request.Header.Set("Authorization", "Bearer "+token)
		injectTraceContext(ctx, request.Header)
		response, err := client.Do(request)
		if err != nil {
			return session{}, err
		}
		defer response.Body.Close()
		var result session
		if response.StatusCode != http.StatusOK {
			return result, errors.New("chat session rejected")
		}
		err = json.NewDecoder(io.LimitReader(response.Body, 1024)).Decode(&result)
		return result, err
	}
}

func realtimeHandler(ctx context.Context, clients *hub, origins map[string]bool, verify verifier) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		if !clients.available() {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("GET /metrics", func(w http.ResponseWriter, r *http.Request) {
		metrics := clients.metricsSnapshot()
		w.Header().Set("Content-Type", metricsContentType)
		w.WriteHeader(http.StatusOK)
		_, _ = fmt.Fprintf(w, "# HELP bluealbum_chat_realtime_ready Whether the realtime broker is ready (1 for ready, 0 otherwise).\n"+
			"# TYPE bluealbum_chat_realtime_ready gauge\n"+
			"bluealbum_chat_realtime_ready %d\n"+
			"# HELP bluealbum_chat_realtime_connections Number of authenticated realtime connections.\n"+
			"# TYPE bluealbum_chat_realtime_connections gauge\n"+
			"bluealbum_chat_realtime_connections %d\n"+
			"# HELP bluealbum_chat_realtime_notifications_total Number of valid realtime dispatches received by the hub.\n"+
			"# TYPE bluealbum_chat_realtime_notifications_total counter\n"+
			"bluealbum_chat_realtime_notifications_total %d\n",
			readyMetricValue(metrics.ready), metrics.connections, metrics.notifications)
	})
	slots := make(chan struct{}, 512)
	socket := websocket.Server{Handler: func(conn *websocket.Conn) {
		serveSocket(ctx, clients, verify, conn)
	}}
	mux.HandleFunc("GET "+socketPath, func(w http.ResponseWriter, r *http.Request) {
		if !origins[r.Header.Get("Origin")] {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		if !clients.available() {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		socket.ServeHTTP(w, r)
	})
	return mux
}

func serveSocket(ctx context.Context, clients *hub, verify verifier, conn *websocket.Conn) {
	defer func() {
		_ = conn.SetWriteDeadline(time.Now().Add(time.Second))
		_ = conn.Close()
	}()
	conn.MaxPayloadBytes = 8192
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var token string
	if err := websocket.Message.Receive(conn, &token); err != nil || token == "" {
		return
	}
	authContext := extractTraceContext(ctx, conn.Request().Header)
	authContext, span := tracerForContext(authContext).Start(authContext, "chat.realtime.authenticate",
		trace.WithSpanKind(trace.SpanKindServer))
	identity, err := verify(authContext, token)
	valid := err == nil && identity.UserID > 0 && identity.ExpiresAt > time.Now().Unix()
	if !valid {
		span.SetStatus(codes.Error, "session rejected")
	}
	span.End()
	if !valid {
		return
	}
	expiration := time.Unix(identity.ExpiresAt, 0)
	_ = conn.SetReadDeadline(expiration)
	changes, unsubscribe := clients.subscribe(identity.UserID)
	if unsubscribe == nil {
		return
	}
	defer unsubscribe()

	closed := make(chan struct{})
	go func() {
		defer close(closed)
		// The browser sends only the initial credential. Reading also handles control frames.
		var unexpected string
		_ = websocket.Message.Receive(conn, &unexpected)
	}()
	if writeSignal(conn, "ready") != nil {
		return
	}
	heartbeat := time.NewTicker(20 * time.Second)
	defer heartbeat.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-closed:
			return
		case _, open := <-changes:
			if !open || !time.Now().Before(expiration) || writeSignal(conn, "changed") != nil {
				return
			}
		case <-heartbeat.C:
			if writeSignal(conn, "heartbeat") != nil {
				return
			}
		}
	}
}

func writeSignal(conn *websocket.Conn, signal string) error {
	_ = conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	return websocket.Message.Send(conn, signal)
}

type hub struct {
	mu            sync.Mutex
	ready         bool
	clients       map[int64]map[chan struct{}]struct{}
	notifications uint64
}

type hubMetrics struct {
	ready         bool
	connections   int
	notifications uint64
}

func (h *hub) metricsSnapshot() hubMetrics {
	h.mu.Lock()
	defer h.mu.Unlock()

	connections := 0
	for _, userConnections := range h.clients {
		connections += len(userConnections)
	}
	return hubMetrics{
		ready:         h.ready,
		connections:   connections,
		notifications: h.notifications,
	}
}

func readyMetricValue(ready bool) int {
	if ready {
		return 1
	}
	return 0
}

func (h *hub) available() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.ready
}

func (h *hub) setAvailable(ready bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.ready = ready
	if !ready {
		for _, connections := range h.clients {
			for changes := range connections {
				close(changes)
			}
		}
		clear(h.clients)
	}
}

func (h *hub) subscribe(user int64) (<-chan struct{}, func()) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.ready || len(h.clients[user]) >= 4 {
		return nil, nil
	}
	changes := make(chan struct{}, 1)
	if h.clients[user] == nil {
		h.clients[user] = make(map[chan struct{}]struct{})
	}
	h.clients[user][changes] = struct{}{}
	return changes, func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		delete(h.clients[user], changes)
		if len(h.clients[user]) == 0 {
			delete(h.clients, user)
		}
	}
}

func (h *hub) notify(recipients []int64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.notifications++
	for _, user := range recipients {
		for changes := range h.clients[user] {
			select {
			case changes <- struct{}{}:
			default:
				// One pending signal can cover any number of committed changes.
			}
		}
	}
}
