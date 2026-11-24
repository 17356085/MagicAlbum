package main

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"golang.org/x/net/websocket"
)

const (
	dualRealtimeBrokerEnv = "CHAT_REALTIME_TEST_AMQP_URL"
	realtimeTestOrigin    = "http://localhost:5173"
	receiverID            = int64(91001)
	unrelatedID           = int64(91002)
	quietPeriod           = 750 * time.Millisecond
)

// TestDualRealtimeRealBroker exercises two independently hosted realtime hubs
// against one disposable RabbitMQ broker. It is deliberately opt-in: the test
// must never fall back to the developer's default broker URL.
func TestDualRealtimeRealBroker(t *testing.T) {
	brokerEndpoint := isolatedBrokerEndpoint(t)
	rootCtx, cancelRoot := context.WithCancel(context.Background())
	t.Cleanup(cancelRoot)

	ctxA, cancelA := context.WithCancel(rootCtx)
	ctxB, cancelB := context.WithCancel(rootCtx)
	defer cancelA()
	defer cancelB()

	hubA := &hub{clients: make(map[int64]map[chan struct{}]struct{})}
	hubB := &hub{clients: make(map[int64]map[chan struct{}]struct{})}
	verify := func(_ context.Context, token string) (session, error) {
		switch token {
		case "receiver-token":
			return session{UserID: receiverID, ExpiresAt: time.Now().Add(5 * time.Minute).Unix()}, nil
		case "unrelated-token":
			return session{UserID: unrelatedID, ExpiresAt: time.Now().Add(5 * time.Minute).Unix()}, nil
		default:
			return session{}, errors.New("unknown test token")
		}
	}
	origins := map[string]bool{realtimeTestOrigin: true}
	serverA := httptest.NewServer(realtimeHandler(ctxA, hubA, origins, verify))
	serverB := httptest.NewServer(realtimeHandler(ctxB, hubB, origins, verify))
	defer serverA.Close()
	defer serverB.Close()

	go consumeChanges(ctxA, brokerEndpoint, hubA)
	go consumeChanges(ctxB, brokerEndpoint, hubB)
	waitForRealtimeHealth(t, serverA.URL, 15*time.Second)
	waitForRealtimeHealth(t, serverB.URL, 15*time.Second)

	receiverA := dialRealtime(t, serverA.URL, "receiver-token")
	receiverB := dialRealtime(t, serverB.URL, "receiver-token")
	unrelatedA := dialRealtime(t, serverA.URL, "unrelated-token")
	defer receiverB.Close()
	defer unrelatedA.Close()

	expectRealtimeSignal(t, receiverA, "ready")
	expectRealtimeSignal(t, receiverB, "ready")
	expectRealtimeSignal(t, unrelatedA, "ready")

	publishChanged(t, brokerEndpoint, []int64{receiverID})
	expectRealtimeSignal(t, receiverA, "changed")
	expectRealtimeSignal(t, receiverB, "changed")
	expectNoRealtimeSignal(t, receiverA, quietPeriod)
	expectNoRealtimeSignal(t, receiverB, quietPeriod)
	expectNoRealtimeSignal(t, unrelatedA, quietPeriod)

	// Simulate the first node and its connection disappearing. The same
	// receiver then reconnects to the still-running second node.
	_ = receiverA.Close()
	_ = unrelatedA.Close()
	cancelA()
	serverA.CloseClientConnections()
	serverA.Close()

	reconnected := dialRealtime(t, serverB.URL, "receiver-token")
	defer reconnected.Close()
	expectRealtimeSignal(t, reconnected, "ready")

	publishChanged(t, brokerEndpoint, []int64{receiverID})
	expectRealtimeSignal(t, receiverB, "changed")
	expectRealtimeSignal(t, reconnected, "changed")
	expectNoRealtimeSignal(t, receiverB, quietPeriod)
	expectNoRealtimeSignal(t, reconnected, quietPeriod)
}

func isolatedBrokerEndpoint(t *testing.T) string {
	t.Helper()
	endpoint := strings.TrimSpace(os.Getenv(dualRealtimeBrokerEnv))
	if endpoint == "" {
		t.Skipf("set %s to a disposable RabbitMQ URL; the real-broker test never uses the local default", dualRealtimeBrokerEnv)
	}
	parsed, err := url.Parse(endpoint)
	if err != nil || (parsed.Scheme != "amqp" && parsed.Scheme != "amqps") || parsed.Host == "" {
		t.Fatalf("%s must be an amqp:// or amqps:// URL with a host", dualRealtimeBrokerEnv)
	}
	port := parsed.Port()
	if port == "" {
		port = map[string]string{"amqp": "5672", "amqps": "5671"}[parsed.Scheme]
	}
	if (parsed.Hostname() == "127.0.0.1" || parsed.Hostname() == "localhost" || parsed.Hostname() == "::1") && (port == "5672" || port == "5671") {
		t.Fatalf("refusing the default local RabbitMQ port; use the disposable broker script or a dynamically published port")
	}
	return endpoint
}

func waitForRealtimeHealth(t *testing.T, serverURL string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	client := &http.Client{Timeout: time.Second}
	for time.Now().Before(deadline) {
		request, err := http.NewRequest(http.MethodGet, serverURL+"/health", nil)
		if err == nil {
			response, requestErr := client.Do(request)
			if requestErr == nil {
				_ = response.Body.Close()
				if response.StatusCode == http.StatusOK {
					return
				}
			}
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("realtime endpoint %s did not become broker-ready within %s", serverURL, timeout)
}

func dialRealtime(t *testing.T, serverURL, token string) *websocket.Conn {
	t.Helper()
	wsURL := "ws" + strings.TrimPrefix(serverURL, "http") + socketPath
	config, err := websocket.NewConfig(wsURL, realtimeTestOrigin)
	if err != nil {
		t.Fatalf("create websocket config: %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	connection, err := config.DialContext(ctx)
	if err != nil {
		t.Fatalf("dial realtime endpoint %s: %v", serverURL, err)
	}
	if err := websocket.Message.Send(connection, token); err != nil {
		_ = connection.Close()
		t.Fatalf("send realtime credential: %v", err)
	}
	return connection
}

func expectRealtimeSignal(t *testing.T, connection *websocket.Conn, want string) {
	t.Helper()
	if err := connection.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatalf("set websocket read deadline: %v", err)
	}
	var got string
	if err := websocket.Message.Receive(connection, &got); err != nil {
		t.Fatalf("receive realtime signal %q: %v", want, err)
	}
	if got != want {
		t.Fatalf("received realtime signal %q, want %q", got, want)
	}
}

func expectNoRealtimeSignal(t *testing.T, connection *websocket.Conn, quiet time.Duration) {
	t.Helper()
	if err := connection.SetReadDeadline(time.Now().Add(quiet)); err != nil {
		t.Fatalf("set websocket quiet-period deadline: %v", err)
	}
	var got string
	err := websocket.Message.Receive(connection, &got)
	if err == nil {
		t.Fatalf("received unexpected realtime signal %q", got)
	}
	var networkError net.Error
	if !errors.As(err, &networkError) || !networkError.Timeout() {
		t.Fatalf("websocket closed during quiet period instead of timing out: %v", err)
	}
}

func publishChanged(t *testing.T, endpoint string, recipients []int64) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	connection, err := amqp.DialConfig(endpoint, amqp.Config{Dial: amqp.DefaultDial(4 * time.Second)})
	if err != nil {
		t.Fatalf("connect to isolated RabbitMQ: %s", redactBrokerError(endpoint, err))
	}
	defer connection.Close()
	channel, err := connection.Channel()
	if err != nil {
		t.Fatalf("open RabbitMQ channel: %v", err)
	}
	defer channel.Close()
	if err := channel.ExchangeDeclare(changeExchange, "fanout", true, false, false, false, nil); err != nil {
		t.Fatalf("declare realtime exchange: %v", err)
	}
	body, err := json.Marshal(recipients)
	if err != nil {
		t.Fatalf("encode realtime recipients: %v", err)
	}
	if err := channel.PublishWithContext(ctx, changeExchange, "", false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	}); err != nil {
		t.Fatalf("publish realtime change: %v", err)
	}
}

func redactBrokerError(endpoint string, err error) string {
	message := err.Error()
	parsed, parseErr := url.Parse(endpoint)
	if parseErr == nil {
		redacted := *parsed
		redacted.User = nil
		redactedEndpoint := redacted.String()
		message = strings.ReplaceAll(message, endpoint, redactedEndpoint)
		if parsed.User != nil {
			message = strings.ReplaceAll(message, parsed.User.String(), "<credentials>")
		}
	}
	return message
}
