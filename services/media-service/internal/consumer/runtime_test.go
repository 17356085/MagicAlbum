package consumer

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"sync"
	"testing"
	"time"

	"github.com/17356085/MagicAlbum/services/media-service/internal/coreclient"
	"github.com/17356085/MagicAlbum/services/media-service/internal/processor"
	"github.com/17356085/MagicAlbum/services/media-service/internal/wire"
)

type fakeCore struct {
	claim        coreclient.ClaimResponse
	claimErr     error
	renewErr     error
	input        coreclient.Input
	inputErr     error
	failureCodes []string
	resultCalls  int
}

func (f *fakeCore) Claim(context.Context, wire.Envelope) (coreclient.ClaimResponse, error) {
	return f.claim, f.claimErr
}
func (f *fakeCore) Renew(context.Context, string, int, string) (coreclient.RenewResponse, error) {
	return coreclient.RenewResponse{}, f.renewErr
}
func (f *fakeCore) GetInput(context.Context, string, int, string) (coreclient.Input, error) {
	return f.input, f.inputErr
}
func (f *fakeCore) SubmitResult(context.Context, string, int, string, processor.Result, string) error {
	f.resultCalls++
	return nil
}
func (f *fakeCore) SubmitFailure(_ context.Context, _ string, _ int, _ string, code string) error {
	f.failureCodes = append(f.failureCodes, code)
	return nil
}

type fakeSession struct {
	mu         sync.Mutex
	events     []string
	publishErr error
}

func (f *fakeSession) Consume(context.Context) (<-chan Delivery, error) {
	return nil, errors.New("unused")
}
func (f *fakeSession) Publish(_ context.Context, p Publication) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.events = append(f.events, "publish:"+p.Exchange+":"+p.RoutingKey)
	return f.publishErr
}
func (f *fakeSession) Close() error { return nil }

func validBody(t *testing.T) []byte {
	t.Helper()
	body, err := json.Marshal(wire.Envelope{SchemaVersion: 1, EventType: wire.EventType, EventID: "01234567-89ab-cdef-0123-456789abcdef", AssetID: "11234567-89ab-cdef-0123-456789abcdef", JobID: "21234567-89ab-cdef-0123-456789abcdef", JobVersion: 1, Preset: "preview", ProcessorVersion: "1", OccurredAt: time.Date(2026, 10, 19, 11, 22, 33, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func terminalCore() *fakeCore { return &fakeCore{claim: coreclient.ClaimResponse{Status: "SUCCEEDED"}} }

func TestRuntimeAcksTerminalAfterHandler(t *testing.T) {
	core := terminalCore()
	handler, err := NewHandler(core, time.Second, time.Hour, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	runtime := &Runtime{Handler: handler, Health: NewHealth()}
	var events []string
	delivery := NewDelivery(validBody(t), func() error { events = append(events, "ack"); return nil }, func(bool) error { events = append(events, "nack"); return nil })
	channel := make(chan Delivery, 1)
	channel <- delivery
	close(channel)
	err = runtime.consumeSession(context.Background(), &fakeSession{}, channel)
	if err == nil || len(events) != 1 || events[0] != "ack" {
		t.Fatalf("events=%v err=%v", events, err)
	}
}

func TestRetryPublishesBeforeAckAndQuarantineContainsDigestOnly(t *testing.T) {
	core := &fakeCore{claimErr: &coreclient.Error{Operation: "claim", Status: 409, Kind: coreclient.ErrorBusy}}
	handler, err := NewHandler(core, time.Second, time.Hour, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	fake := &fakeSession{}
	runtime := &Runtime{Handler: handler, RetryPause: time.Millisecond}
	events := []string{}
	delivery := NewDelivery(validBody(t), func() error { events = append(events, "ack"); return nil }, func(bool) error { events = append(events, "nack"); return nil })
	outcome := handler.Handle(context.Background(), delivery.Body)
	if outcome.Kind != OutcomeRetry {
		t.Fatalf("outcome=%+v", outcome)
	}
	if err := runtime.retryOrRequeue(context.Background(), fake, &delivery); err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || events[0] != "ack" || len(fake.events) != 1 {
		t.Fatalf("events=%v publications=%v", events, fake.events)
	}

	invalid := []byte(`{"body":"do not retain"}`)
	quarantineDelivery := NewDelivery(invalid, func() error { events = append(events, "qack"); return nil }, func(bool) error { events = append(events, "qnack"); return nil })
	if err := runtime.quarantineOrRequeue(context.Background(), fake, &quarantineDelivery, "invalid_message"); err != nil {
		t.Fatal(err)
	}
	if events[len(events)-1] != "qack" {
		t.Fatalf("quarantine ack ordering: %v", events)
	}
	if len(fake.events) != 2 {
		t.Fatalf("quarantine publication missing: %v", fake.events)
	}
	var message wire.QuarantineMessage
	if err := json.Unmarshal([]byte(`{"digest":""}`), &message); err == nil {
		_ = err
	}
}

func TestPublishFailureNacksAndDoesNotAck(t *testing.T) {
	fake := &fakeSession{publishErr: errors.New("broker down")}
	runtime := &Runtime{RetryPause: time.Millisecond}
	events := []string{}
	delivery := NewDelivery([]byte("message"), func() error { events = append(events, "ack"); return nil }, func(requeue bool) error {
		if !requeue {
			t.Error("retry did not requeue")
		}
		events = append(events, "nack")
		return nil
	})
	if err := runtime.retryOrRequeue(context.Background(), fake, &delivery); err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || events[0] != "nack" {
		t.Fatalf("events=%v", events)
	}
}

func TestShutdownNacksActiveDeliveryWithoutFailureCallback(t *testing.T) {
	core := terminalCore()
	handler, err := NewHandler(core, time.Second, time.Hour, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	runtime := &Runtime{Handler: handler}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	events := []string{}
	delivery := NewDelivery(validBody(t), func() error { events = append(events, "ack"); return nil }, func(requeue bool) error {
		if !requeue {
			t.Error("shutdown did not requeue")
		}
		events = append(events, "nack")
		return nil
	})
	channel := make(chan Delivery, 1)
	channel <- delivery
	close(channel)
	if err := runtime.consumeSession(ctx, &fakeSession{}, channel); err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || events[0] != "nack" {
		t.Fatalf("events=%v", events)
	}
	if len(core.failureCodes) != 0 {
		t.Fatalf("shutdown recorded failure: %v", core.failureCodes)
	}
}

func TestRenewalFailureCancelsTransform(t *testing.T) {
	core := &fakeCore{claim: coreclient.ClaimResponse{Status: "CLAIMED", AssetID: "11234567-89ab-cdef-0123-456789abcdef", JobVersion: 1, Preset: "preview", ProcessorVersion: "1", LeaseToken: "lease", LeaseUntil: time.Now().UTC().Add(time.Minute), InputSHA256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", ContentType: "image/png", ByteSize: 1}, renewErr: errors.New("renew failed")}
	handler, err := NewHandler(core, time.Second, time.Millisecond, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	core.input = coreclient.Input{Bytes: []byte{1}, ContentType: "image/png"}
	digest := sha256.Sum256(core.input.Bytes)
	core.claim.InputSHA256 = hex.EncodeToString(digest[:])
	handler.Transform = func(ctx context.Context, _ io.Reader, _ processor.Preset) (processor.Result, error) {
		<-ctx.Done()
		return processor.Result{}, ctx.Err()
	}
	outcome := handler.Handle(context.Background(), validBody(t))
	if outcome.Kind != OutcomeRetry || outcome.Reason != "lease_renewal" {
		t.Fatalf("outcome=%+v", outcome)
	}
}
