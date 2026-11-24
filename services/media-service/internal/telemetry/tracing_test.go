package telemetry_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/17356085/MagicAlbum/services/media-service/internal/consumer"
	"github.com/17356085/MagicAlbum/services/media-service/internal/coreclient"
	"github.com/17356085/MagicAlbum/services/media-service/internal/processor"
	"github.com/17356085/MagicAlbum/services/media-service/internal/telemetry"
	"github.com/17356085/MagicAlbum/services/media-service/internal/wire"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

const (
	testTraceID = "4bf92f3577b34da6a3ce929d0e0e4736"
	testSpanID  = "00f067aa0ba902b7"
	testParent  = "00-" + testTraceID + "-" + testSpanID + "-01"
)

func TestMediaProcessSpanUsesProducerParentAndRetryKeepsOnlyW3CHeaders(t *testing.T) {
	ctx, recorder := recordingContext(t)
	traceID, err := trace.TraceIDFromHex(testTraceID)
	if err != nil {
		t.Fatal(err)
	}
	parentID, err := trace.SpanIDFromHex(testSpanID)
	if err != nil {
		t.Fatal(err)
	}
	traceState, err := trace.ParseTraceState("vendor=opaque")
	if err != nil {
		t.Fatal(err)
	}
	parent := trace.NewSpanContext(trace.SpanContextConfig{TraceID: traceID, SpanID: parentID, TraceFlags: trace.FlagsSampled, TraceState: traceState, Remote: true})
	ctx = trace.ContextWithRemoteSpanContext(ctx, parent)
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	published := make(chan consumer.Publication, 1)
	acked := make(chan struct{}, 1)
	session := &traceSession{deliveries: make(chan consumer.Delivery, 1), published: published}
	broker := &traceBroker{session: session}
	body, err := json.Marshal(wire.Envelope{
		SchemaVersion:    wire.SchemaVersion,
		EventType:        wire.EventType,
		EventID:          "01234567-89ab-cdef-0123-456789abcdef",
		AssetID:          "11234567-89ab-cdef-0123-456789abcdef",
		JobID:            "21234567-89ab-cdef-0123-456789abcdef",
		JobVersion:       1,
		Preset:           wire.PresetPreview,
		ProcessorVersion: wire.ProcessorVersion,
		OccurredAt:       time.Date(2026, 11, 25, 1, 2, 3, 0, time.UTC),
	})
	if err != nil {
		t.Fatal(err)
	}
	delivery := consumer.NewDelivery(body, func() error {
		acked <- struct{}{}
		cancel()
		return nil
	}, func(bool) error { return errors.New("unexpected nack") })
	delivery.Headers = map[string]string{
		"traceparent": testParent,
		"tracestate":  "vendor=opaque",
		"baggage":     "email=private",
	}
	session.deliveries <- delivery

	core := retryCore{}
	handler, err := consumer.NewHandler(core, time.Second, time.Hour, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	runtime := &consumer.Runtime{Broker: broker, Handler: handler, RetryPause: time.Millisecond}
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()

	select {
	case publication := <-published:
		if string(publication.Body) != string(body) {
			t.Fatal("retry publication changed the wire envelope")
		}
		if publication.Headers["baggage"] != "" || len(publication.Headers) != 2 {
			t.Fatalf("retry publication propagated unexpected headers: %#v", publication.Headers)
		}
		if publication.Headers["tracestate"] != "vendor=opaque" {
			t.Fatalf("retry tracestate=%q", publication.Headers["tracestate"])
		}
		spans := recorder.Ended()
		if len(spans) != 1 {
			t.Fatalf("wanted one finite process span, got %d", len(spans))
		}
		span := spans[0]
		if span.Name() != "media.jobs process" || span.SpanKind() != trace.SpanKindConsumer || span.Parent().SpanID() != parentID || span.SpanContext().TraceID() != traceID {
			t.Fatalf("unexpected process span: name=%q kind=%v parent=%s trace=%s", span.Name(), span.SpanKind(), span.Parent().SpanID(), span.SpanContext().TraceID())
		}
		if len(span.Attributes()) != 0 || len(span.Events()) != 0 || len(span.Links()) != 0 {
			t.Fatal("process span captured unexpected data")
		}
		wantParent := "00-" + testTraceID + "-" + span.SpanContext().SpanID().String() + "-01"
		if publication.Headers["traceparent"] != wantParent {
			t.Fatalf("retry parent=%q, want %q", publication.Headers["traceparent"], wantParent)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("retry publication was not observed")
	}
	select {
	case <-acked:
	case <-time.After(2 * time.Second):
		t.Fatal("delivery was not acknowledged")
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("runtime did not stop after cancellation")
	}
}

func TestDisabledTracingDoesNotCreateExporterOrAcceptPropagation(t *testing.T) {
	t.Setenv("OTEL_TRACES_EXPORTER", "none")
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "not a URL")
	system := context.Background()
	ctx, shutdown := telemetry.ConfigureTracing(system)
	if ctx != system || shutdown(context.Background()) != nil {
		t.Fatal("disabled tracing changed the context or shutdown behavior")
	}
	header := telemetry.TraceHeaders{"traceparent": testParent, "baggage": "email=private"}
	if got := telemetry.ExtractTraceContext(ctx, header); trace.SpanContextFromContext(got).IsValid() {
		t.Fatal("disabled tracing accepted an inbound trace")
	}
	telemetry.InjectTraceContext(ctx, header)
	if header["baggage"] != "email=private" || header["traceparent"] != testParent {
		t.Fatal("disabled tracing modified propagation headers")
	}
}

func recordingContext(t *testing.T) (context.Context, *tracetest.SpanRecorder) {
	t.Helper()
	recorder := tracetest.NewSpanRecorder()
	provider := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(recorder))
	t.Cleanup(func() {
		if err := provider.Shutdown(context.Background()); err != nil {
			t.Error(err)
		}
	})
	return telemetry.WithTracer(context.Background(), provider.Tracer("media-test")), recorder
}

type traceBroker struct{ session *traceSession }

func (b *traceBroker) Connect(context.Context) (consumer.Session, error) { return b.session, nil }

type traceSession struct {
	deliveries chan consumer.Delivery
	published  chan consumer.Publication
}

func (s *traceSession) Consume(context.Context) (<-chan consumer.Delivery, error) {
	if s.deliveries == nil {
		s.deliveries = make(chan consumer.Delivery)
	}
	return s.deliveries, nil
}

func (s *traceSession) Publish(_ context.Context, publication consumer.Publication) error {
	s.published <- publication
	return nil
}

func (s *traceSession) Close() error { return nil }

type retryCore struct{}

func (retryCore) Claim(context.Context, wire.Envelope) (coreclient.ClaimResponse, error) {
	return coreclient.ClaimResponse{}, &coreclient.Error{Operation: "claim", Status: 409, Kind: coreclient.ErrorBusy}
}

func (retryCore) Renew(context.Context, string, int, string) (coreclient.RenewResponse, error) {
	return coreclient.RenewResponse{}, nil
}

func (retryCore) GetInput(context.Context, string, int, string) (coreclient.Input, error) {
	return coreclient.Input{}, nil
}

func (retryCore) SubmitResult(context.Context, string, int, string, processor.Result, string) error {
	return nil
}

func (retryCore) SubmitFailure(context.Context, string, int, string, string) error { return nil }
