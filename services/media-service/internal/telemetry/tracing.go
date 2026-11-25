package telemetry

import (
	"context"
	"errors"
	"log"
	"os"
	"strings"
	"sync"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
	"go.opentelemetry.io/otel/trace/noop"
)

const (
	otelTracesExporterEnv = "OTEL_TRACES_EXPORTER"
	defaultServiceName    = "bluealbum-media-worker"
	instrumentationName   = "github.com/17356085/MagicAlbum/services/media-service"
)

// traceContextPropagator is deliberately limited to W3C trace context. The
// worker never accepts or emits baggage.
var traceContextPropagator = propagation.TraceContext{}
var noopTracer = noop.NewTracerProvider().Tracer(instrumentationName)

type tracingContextKey struct{}

// TraceHeaders is an AMQP-safe carrier that exposes only the two W3C trace
// context fields. It intentionally ignores all other keys, including baggage.
type TraceHeaders map[string]string

func (h TraceHeaders) Get(key string) string {
	switch key {
	case "traceparent", "tracestate":
		return h[key]
	default:
		return ""
	}
}

func (h TraceHeaders) Set(key, value string) {
	if h == nil {
		return
	}
	switch key {
	case "traceparent", "tracestate":
		h[key] = value
	}
}

func (h TraceHeaders) Keys() []string {
	keys := make([]string, 0, 2)
	if h.Get("traceparent") != "" {
		keys = append(keys, "traceparent")
	}
	if h.Get("tracestate") != "" {
		keys = append(keys, "tracestate")
	}
	return keys
}

// ConfigureTracing installs an OTLP HTTP/protobuf exporter only when the
// exporter is explicitly enabled. The default path is a true no-op and does
// not construct an exporter or make a network call.
func ConfigureTracing(ctx context.Context) (context.Context, func(context.Context) error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if !otlpTracingEnabled() {
		return ctx, noTracingShutdown
	}

	exporter, err := otlptracehttp.New(ctx)
	if err != nil {
		log.Print("media worker tracing could not initialize")
		return ctx, noTracingShutdown
	}
	serviceName := strings.TrimSpace(os.Getenv("OTEL_SERVICE_NAME"))
	if serviceName == "" {
		serviceName = defaultServiceName
	}
	serviceResource := resource.NewWithAttributes("", attribute.String("service.name", serviceName))
	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(serviceResource),
	)
	// Keep exporter failures from surfacing endpoint URLs or configured
	// headers through the default global error logger.
	var exportFailure sync.Once
	otel.SetErrorHandler(otel.ErrorHandlerFunc(func(error) {
		exportFailure.Do(func() { log.Print("media worker tracing export failed") })
	}))

	tracer := provider.Tracer(instrumentationName)
	return context.WithValue(ctx, tracingContextKey{}, tracer), func(shutdownContext context.Context) error {
		if shutdownContext == nil {
			shutdownContext = context.Background()
		}
		if err := provider.Shutdown(shutdownContext); err != nil {
			return errors.New("media worker tracing shutdown failed")
		}
		return nil
	}
}

func otlpTracingEnabled() bool {
	return strings.EqualFold(strings.TrimSpace(os.Getenv(otelTracesExporterEnv)), "otlp")
}

func noTracingShutdown(context.Context) error { return nil }

// WithTracer is useful for in-memory instrumentation tests and keeps the
// tracer scoped to a context rather than mutating the process-wide provider.
func WithTracer(ctx context.Context, tracer trace.Tracer) context.Context {
	if ctx == nil {
		ctx = context.Background()
	}
	if tracer == nil {
		return ctx
	}
	return context.WithValue(ctx, tracingContextKey{}, tracer)
}

func tracerForContext(ctx context.Context) trace.Tracer {
	if ctx != nil {
		if tracer, ok := ctx.Value(tracingContextKey{}).(trace.Tracer); ok && tracer != nil {
			return tracer
		}
	}
	return noopTracer
}

// StartSpan starts a span using the tracer attached to ctx. No-op contexts
// remain no-op, so disabled tracing cannot accidentally accept propagation.
func StartSpan(ctx context.Context, name string, options ...trace.SpanStartOption) (context.Context, trace.Span) {
	if ctx == nil {
		ctx = context.Background()
	}
	return tracerForContext(ctx).Start(ctx, name, options...)
}

// ExtractTraceContext accepts only carriers that expose W3C trace context.
// Callers should use TraceHeaders for AMQP and the OpenTelemetry HTTP carrier
// for Core requests.
func ExtractTraceContext(ctx context.Context, carrier propagation.TextMapCarrier) context.Context {
	if ctx == nil {
		ctx = context.Background()
	}
	if carrier == nil || tracerForContext(ctx) == noopTracer {
		return ctx
	}
	return traceContextPropagator.Extract(ctx, carrier)
}

// InjectTraceContext emits only traceparent and tracestate. It never emits
// baggage, even if the source context contains baggage from another library.
func InjectTraceContext(ctx context.Context, carrier propagation.TextMapCarrier) {
	if ctx == nil || carrier == nil || tracerForContext(ctx) == noopTracer {
		return
	}
	traceContextPropagator.Inject(ctx, carrier)
}
