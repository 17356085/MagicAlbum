package main

import (
	"context"
	"errors"
	"log"
	"net/http"
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
	otelServiceNameEnv    = "OTEL_SERVICE_NAME"
	defaultServiceName    = "bluealbum-chat-realtime"
	instrumentationName   = "github.com/17356085/MagicAlbum/services/chat-realtime"
)

// traceContextPropagator is deliberately only the W3C trace-context format.
// In particular, baggage is not accepted or sent by this process.
var traceContextPropagator = propagation.TraceContext{}
var noopTracer = noop.NewTracerProvider().Tracer(instrumentationName)

type tracingContextKey struct{}

// configureTracing installs the SDK only when OTLP export was explicitly
// requested. The no-op path does not construct an exporter and therefore does
// not make a network call. Exporter configuration failures disable tracing but
// leave authentication and message delivery available.
func configureTracing(ctx context.Context) (context.Context, func(context.Context) error) {
	if !otlpTracingEnabled() {
		return ctx, noTracingShutdown
	}

	exporter, err := otlptracehttp.New(ctx)
	if err != nil {
		log.Print("chat realtime tracing could not initialize")
		return ctx, noTracingShutdown
	}

	serviceName := strings.TrimSpace(os.Getenv(otelServiceNameEnv))
	if serviceName == "" {
		serviceName = defaultServiceName
	}
	serviceResource := resource.NewWithAttributes(
		"",
		attribute.String("service.name", serviceName),
	)
	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(serviceResource),
	)
	// Keep SDK/exporter failures from surfacing endpoint URLs or configured
	// headers through the default global error logger.
	var exportFailure sync.Once
	otel.SetErrorHandler(otel.ErrorHandlerFunc(func(error) {
		exportFailure.Do(func() { log.Print("chat realtime tracing export failed") })
	}))

	tracer := provider.Tracer(instrumentationName)
	return context.WithValue(ctx, tracingContextKey{}, tracer), func(shutdownContext context.Context) error {
		if err := provider.Shutdown(shutdownContext); err != nil {
			// Do not expose exporter URLs, credentials, or request headers.
			return errors.New("chat realtime tracing shutdown failed")
		}
		return nil
	}
}

func otlpTracingEnabled() bool {
	return strings.EqualFold(strings.TrimSpace(os.Getenv(otelTracesExporterEnv)), "otlp")
}

func noTracingShutdown(context.Context) error { return nil }

func tracerForContext(ctx context.Context) trace.Tracer {
	if tracer, ok := ctx.Value(tracingContextKey{}).(trace.Tracer); ok && tracer != nil {
		return tracer
	}
	return noopTracer
}

func contextWithTracer(ctx context.Context, tracer trace.Tracer) context.Context {
	if tracer == nil {
		return ctx
	}
	return context.WithValue(ctx, tracingContextKey{}, tracer)
}

func extractTraceContext(ctx context.Context, headers http.Header) context.Context {
	if tracerForContext(ctx) == noopTracer || headers == nil {
		return ctx
	}
	return traceContextPropagator.Extract(ctx, propagation.HeaderCarrier(headers))
}

func injectTraceContext(ctx context.Context, headers http.Header) {
	if headers == nil {
		return
	}
	traceContextPropagator.Inject(ctx, propagation.HeaderCarrier(headers))
}
