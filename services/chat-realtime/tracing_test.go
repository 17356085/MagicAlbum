package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
	"golang.org/x/net/websocket"
)

const rehearsalTrace = "4bf92f3577b34da6a3ce929d0e0e4736"
const rehearsalSpan = "00f067aa0ba902b7"
const rehearsalParent = "00-" + rehearsalTrace + "-" + rehearsalSpan + "-01"

func spanRecorder(t *testing.T) (context.Context, *tracetest.SpanRecorder) {
	t.Helper()
	recorder := tracetest.NewSpanRecorder()
	provider := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(recorder))
	t.Cleanup(func() {
		if err := provider.Shutdown(context.Background()); err != nil {
			t.Error(err)
		}
	})
	return contextWithTracer(context.Background(), provider.Tracer("rehearsal")), recorder
}

func TestTracesOnlyWebSocketAuthenticationAndForwardsW3CContextToHTTP(t *testing.T) {
	ctx, recorder := spanRecorder(t)
	inbound := make(chan http.Header, 1)
	core := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		inbound <- request.Header.Clone()
		_, _ = response.Write([]byte(`{"userId":7,"expiresAt":4102444800}`))
	}))
	defer core.Close()
	clients := &hub{clients: make(map[int64]map[chan struct{}]struct{})}
	clients.setAvailable(true)
	origin := "http://localhost:5173"
	server := httptest.NewServer(realtimeHandler(ctx, clients, map[string]bool{origin: true},
		sessionVerifier(core.URL, core.Client())))
	defer server.Close()

	config, err := websocket.NewConfig(strings.Replace(server.URL, "http:", "ws:", 1)+socketPath, origin)
	if err != nil {
		t.Fatal(err)
	}
	config.Header.Set("traceparent", rehearsalParent)
	config.Header.Set("tracestate", "vendor=opaque")
	config.Header.Set("baggage", "email=private")
	connection, err := websocket.DialConfig(config)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if err := connection.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if err := websocket.Message.Send(connection, "test-secret"); err != nil {
		t.Fatal(err)
	}
	var signal string
	if err := websocket.Message.Receive(connection, &signal); err != nil || signal != "ready" {
		t.Fatalf("session did not become ready: %v", err)
	}

	// The two spans must already have finished while the WebSocket is still open.
	spans := recorder.Ended()
	if len(spans) != 2 || spans[0].Name() != "chat.session.verify" || spans[1].Name() != "chat.realtime.authenticate" {
		t.Fatalf("wanted a finite auth trace, got %d spans", len(spans))
	}
	caller, socket := spans[0], spans[1]
	if socket.Parent().SpanID().String() != rehearsalSpan || caller.Parent().SpanID() != socket.SpanContext().SpanID() {
		t.Fatal("trace parent did not cross the WebSocket authentication boundary")
	}
	header := <-inbound
	if want := "00-" + rehearsalTrace + "-" + caller.SpanContext().SpanID().String() + "-01"; header.Get("traceparent") != want {
		t.Fatal("the Go HTTP verifier did not send the current trace context")
	}
	if header.Get("tracestate") != "vendor=opaque" || header.Get("baggage") != "" {
		t.Fatal("the Go HTTP verifier did not restrict propagation to W3C trace context")
	}
	if len(caller.Attributes()) != 0 || len(socket.Attributes()) != 0 {
		t.Fatal("auth tracing captured unexpected attributes")
	}
}

func TestTracesOnlyValidRabbitSignalsWithAProducerParent(t *testing.T) {
	ctx, recorder := spanRecorder(t)
	clients := &hub{clients: make(map[int64]map[chan struct{}]struct{})}
	clients.setAvailable(true)
	signals, unsubscribe := clients.subscribe(7)
	defer unsubscribe()
	notifyDelivery(ctx, clients, amqp.Delivery{Body: []byte("not json")})
	notifyDelivery(ctx, clients, amqp.Delivery{
		Body: []byte("[7]"),
		Headers: amqp.Table{
			"traceparent": []byte(rehearsalParent),
			"tracestate":  "vendor=opaque",
		},
	})
	select {
	case <-signals:
	default:
		t.Fatal("valid signal was not dispatched")
	}
	spans := recorder.Ended()
	if len(spans) != 1 {
		t.Fatalf("expected one consumer span, got %d", len(spans))
	}
	span := spans[0]
	if span.SpanKind() != trace.SpanKindConsumer || span.Parent().SpanID().String() != rehearsalSpan ||
		span.SpanContext().TraceID().String() != rehearsalTrace || len(span.Attributes()) != 2 {
		t.Fatal("consumer trace did not preserve its parent and limited attributes")
	}
}

func TestTracingDisabledDoesNotCreateAnExporterOrAcceptHeaders(t *testing.T) {
	t.Setenv(otelTracesExporterEnv, "none")
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "not a URL")
	system := context.Background()
	ctx, shutdown := configureTracing(system)
	if ctx != system || tracerForContext(ctx) != noopTracer || shutdown(ctx) != nil {
		t.Fatal("disabled tracing started an exporter")
	}
	inbound := make(http.Header)
	inbound.Set("traceparent", rehearsalParent)
	if trace.SpanContextFromContext(extractTraceContext(ctx, inbound)).IsValid() {
		t.Fatal("disabled tracing accepted an inbound trace")
	}
}
