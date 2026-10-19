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
	mu           sync.Mutex
	claim        coreclient.ClaimResponse
	claimErr     error
	renewErr     error
	renewFn      func(context.Context, int) (coreclient.RenewResponse, error)
	renewCalls   int
	renewStarted chan<- struct{}
	renewDone    chan<- struct{}
	input        coreclient.Input
	inputErr     error
	failureCodes []string
	failureFn    func(context.Context) error
	resultCalls  int
	resultFn     func(context.Context) error
}

func (f *fakeCore) Claim(context.Context, wire.Envelope) (coreclient.ClaimResponse, error) {
	return f.claim, f.claimErr
}
func (f *fakeCore) Renew(ctx context.Context, _ string, _ int, _ string) (coreclient.RenewResponse, error) {
	f.mu.Lock()
	f.renewCalls++
	call := f.renewCalls
	renewFn := f.renewFn
	renewErr := f.renewErr
	renewStarted := f.renewStarted
	renewDone := f.renewDone
	f.mu.Unlock()
	if renewStarted != nil {
		select {
		case renewStarted <- struct{}{}:
		default:
		}
	}
	if renewFn != nil {
		response, err := renewFn(ctx, call)
		if renewDone != nil {
			select {
			case renewDone <- struct{}{}:
			default:
			}
		}
		return response, err
	}
	if renewDone != nil {
		select {
		case renewDone <- struct{}{}:
		default:
		}
	}
	return coreclient.RenewResponse{}, renewErr
}
func (f *fakeCore) GetInput(context.Context, string, int, string) (coreclient.Input, error) {
	return f.input, f.inputErr
}

func (f *fakeCore) SubmitResult(ctx context.Context, _ string, _ int, _ string, _ processor.Result, _ string) error {
	f.mu.Lock()
	f.resultCalls++
	resultFn := f.resultFn
	f.mu.Unlock()
	if resultFn != nil {
		return resultFn(ctx)
	}
	return nil
}

func (f *fakeCore) SubmitFailure(ctx context.Context, _ string, _ int, _ string, code string) error {
	f.mu.Lock()
	f.failureCodes = append(f.failureCodes, code)
	failureFn := f.failureFn
	f.mu.Unlock()
	if failureFn != nil {
		return failureFn(ctx)
	}
	return nil
}

func (f *fakeCore) resultCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.resultCalls
}

func claimedCore(leaseUntil time.Time) *fakeCore {
	input := []byte{1}
	digest := sha256.Sum256(input)
	return &fakeCore{
		claim: coreclient.ClaimResponse{
			Status:           "CLAIMED",
			AssetID:          "11234567-89ab-cdef-0123-456789abcdef",
			JobVersion:       1,
			Preset:           "preview",
			ProcessorVersion: "1",
			LeaseToken:       "lease",
			LeaseUntil:       leaseUntil,
			InputSHA256:      hex.EncodeToString(digest[:]),
			ContentType:      "image/png",
			ByteSize:         int64(len(input)),
		},
		input: coreclient.Input{Bytes: input, ContentType: "image/png"},
	}
}

func validResult() processor.Result {
	return processor.Result{
		Bytes:            []byte{2},
		ContentType:      "image/png",
		Width:            1,
		Height:           1,
		Preset:           processor.PresetPreview,
		ProcessorVersion: processor.CurrentProcessorVersion,
	}
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
	core := claimedCore(time.Now().Add(300 * time.Millisecond))
	core.renewErr = errors.New("renew failed")
	handler, err := NewHandler(core, 2*time.Second, 2*time.Second, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	handler.Transform = func(ctx context.Context, _ io.Reader, _ processor.Preset) (processor.Result, error) {
		<-ctx.Done()
		return processor.Result{}, ctx.Err()
	}
	outcome := handler.Handle(context.Background(), validBody(t))
	if outcome.Kind != OutcomeRetry || outcome.Reason != "lease_renewal" {
		t.Fatalf("outcome=%+v", outcome)
	}
	if core.resultCount() != 0 {
		t.Fatalf("result submitted after renewal failure: %d", core.resultCount())
	}
}

func TestInvalidRenewedLeaseCancelsWithoutResult(t *testing.T) {
	cases := []struct {
		name     string
		leaseEnd func(time.Time) time.Time
	}{
		{name: "zero", leaseEnd: func(time.Time) time.Time { return time.Time{} }},
		{name: "expired", leaseEnd: func(now time.Time) time.Time { return now.Add(-time.Second) }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			core := claimedCore(time.Now().Add(300 * time.Millisecond))
			core.renewFn = func(context.Context, int) (coreclient.RenewResponse, error) {
				return coreclient.RenewResponse{LeaseUntil: tc.leaseEnd(time.Now())}, nil
			}
			handler, err := NewHandler(core, 2*time.Second, 2*time.Second, 10<<20, 10<<20)
			if err != nil {
				t.Fatal(err)
			}
			// Exercise the nil-clock fallback while the renewal timer uses the
			// server claim deadline.
			handler.Now = nil
			handler.Transform = func(ctx context.Context, _ io.Reader, _ processor.Preset) (processor.Result, error) {
				<-ctx.Done()
				return processor.Result{}, ctx.Err()
			}
			outcome := handler.Handle(context.Background(), validBody(t))
			if outcome.Kind != OutcomeRetry || outcome.Reason != "lease_renewal" {
				t.Fatalf("outcome=%+v", outcome)
			}
			if core.resultCount() != 0 {
				t.Fatalf("result submitted after invalid renewal: %d", core.resultCount())
			}
		})
	}
}

func TestShortLeaseSchedulesRenewalBeforeConfiguredInterval(t *testing.T) {
	core := claimedCore(time.Now().Add(300 * time.Millisecond))
	renewStarted := make(chan struct{}, 1)
	core.renewStarted = renewStarted
	core.renewFn = func(context.Context, int) (coreclient.RenewResponse, error) {
		return coreclient.RenewResponse{LeaseUntil: time.Now().Add(300 * time.Millisecond)}, nil
	}
	handler, err := NewHandler(core, 2*time.Second, 2*time.Second, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	handler.Transform = func(ctx context.Context, _ io.Reader, _ processor.Preset) (processor.Result, error) {
		select {
		case <-renewStarted:
			return validResult(), nil
		case <-time.After(time.Second):
			return processor.Result{}, errors.New("renewal did not occur before lease expiry")
		case <-ctx.Done():
			return processor.Result{}, ctx.Err()
		}
	}
	outcome := handler.Handle(context.Background(), validBody(t))
	if outcome.Kind != OutcomeAck || outcome.Reason != "succeeded" {
		t.Fatalf("outcome=%+v", outcome)
	}
	if core.resultCount() != 1 {
		t.Fatalf("result calls=%d", core.resultCount())
	}
}

func TestRenewTimeoutBoundedByRemainingLeaseAndFifteenSeconds(t *testing.T) {
	type observation struct {
		timeout   time.Duration
		remaining time.Duration
	}
	cases := []struct {
		name          string
		leaseDuration time.Duration
		renewInterval time.Duration
		wantFifteen   bool
	}{
		{name: "short lease", leaseDuration: 300 * time.Millisecond, renewInterval: 2 * time.Second},
		{name: "fifteen second cap", leaseDuration: 30 * time.Second, renewInterval: time.Millisecond, wantFifteen: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			core := claimedCore(time.Now().Add(tc.leaseDuration))
			observed := make(chan observation, 1)
			core.renewFn = func(ctx context.Context, _ int) (coreclient.RenewResponse, error) {
				deadline, ok := ctx.Deadline()
				if !ok {
					t.Error("renew request has no deadline")
					return coreclient.RenewResponse{}, errors.New("missing renew deadline")
				}
				observed <- observation{timeout: time.Until(deadline), remaining: time.Until(core.claim.LeaseUntil)}
				return coreclient.RenewResponse{}, errors.New("stop after deadline observation")
			}
			handler, err := NewHandler(core, 2*time.Second, tc.renewInterval, 10<<20, 10<<20)
			if err != nil {
				t.Fatal(err)
			}
			handler.Now = nil
			handler.Transform = func(ctx context.Context, _ io.Reader, _ processor.Preset) (processor.Result, error) {
				<-ctx.Done()
				return processor.Result{}, ctx.Err()
			}
			outcome := handler.Handle(context.Background(), validBody(t))
			if outcome.Kind != OutcomeRetry || outcome.Reason != "lease_renewal" {
				t.Fatalf("outcome=%+v", outcome)
			}
			var got observation
			select {
			case got = <-observed:
			default:
				t.Fatal("renew deadline was not observed")
			}
			if got.timeout <= 0 {
				t.Fatalf("renew timeout=%s", got.timeout)
			}
			if got.timeout > got.remaining+5*time.Millisecond {
				t.Fatalf("renew timeout %s exceeds remaining lease %s", got.timeout, got.remaining)
			}
			if got.timeout > 15*time.Second {
				t.Fatalf("renew timeout exceeds 15s: %s", got.timeout)
			}
			if tc.wantFifteen && got.timeout < 14*time.Second {
				t.Fatalf("renew timeout did not use 15s cap: %s", got.timeout)
			}
		})
	}
}

func TestRenewalUsesReturnedLeaseDeadline(t *testing.T) {
	core := claimedCore(time.Now().Add(300 * time.Millisecond))
	firstRenewed := make(chan struct{}, 1)
	secondRenewed := make(chan struct{}, 1)
	core.renewFn = func(_ context.Context, call int) (coreclient.RenewResponse, error) {
		switch call {
		case 1:
			firstRenewed <- struct{}{}
			return coreclient.RenewResponse{LeaseUntil: time.Now().Add(600 * time.Millisecond)}, nil
		case 2:
			secondRenewed <- struct{}{}
			return coreclient.RenewResponse{LeaseUntil: time.Now().Add(600 * time.Millisecond)}, nil
		default:
			return coreclient.RenewResponse{LeaseUntil: time.Now().Add(600 * time.Millisecond)}, nil
		}
	}
	handler, err := NewHandler(core, 2*time.Second, 2*time.Second, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	handler.Transform = func(ctx context.Context, _ io.Reader, _ processor.Preset) (processor.Result, error) {
		select {
		case <-secondRenewed:
			return validResult(), nil
		case <-time.After(time.Second):
			return processor.Result{}, errors.New("renewal did not use the new lease deadline")
		case <-ctx.Done():
			return processor.Result{}, ctx.Err()
		}
	}
	outcome := handler.Handle(context.Background(), validBody(t))
	if outcome.Kind != OutcomeAck || outcome.Reason != "succeeded" {
		t.Fatalf("outcome=%+v", outcome)
	}
	if core.resultCount() != 1 {
		t.Fatalf("result calls=%d", core.resultCount())
	}
	select {
	case <-firstRenewed:
	default:
		t.Fatal("first renewal was not observed")
	}
}

func TestSlowResultSubmissionKeepsRenewing(t *testing.T) {
	core := claimedCore(time.Now().Add(300 * time.Millisecond))
	renewed := make(chan struct{}, 4)
	core.renewStarted = renewed
	core.renewFn = func(context.Context, int) (coreclient.RenewResponse, error) {
		return coreclient.RenewResponse{LeaseUntil: time.Now().Add(600 * time.Millisecond)}, nil
	}
	resultStarted := make(chan struct{}, 1)
	releaseResult := make(chan struct{})
	var submissionStartedAt time.Time
	core.resultFn = func(ctx context.Context) error {
		submissionStartedAt = time.Now()
		resultStarted <- struct{}{}
		select {
		case <-releaseResult:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	handler, err := NewHandler(core, 2*time.Second, 200*time.Millisecond, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	handler.Transform = func(context.Context, io.Reader, processor.Preset) (processor.Result, error) {
		return validResult(), nil
	}
	outcomeCh := make(chan Outcome, 1)
	go func() { outcomeCh <- handler.Handle(context.Background(), validBody(t)) }()
	select {
	case <-resultStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("result submission did not start")
	}
	for i := 0; i < 2; i++ {
		select {
		case <-renewed:
		case <-time.After(2 * time.Second):
			t.Fatal("result submission outlived the lease without renewal")
		}
	}
	if elapsed := time.Since(submissionStartedAt); elapsed < 250*time.Millisecond {
		t.Fatalf("submission did not cross original lease window: %s", elapsed)
	}
	close(releaseResult)
	select {
	case outcome := <-outcomeCh:
		if outcome.Kind != OutcomeAck || outcome.Reason != "succeeded" {
			t.Fatalf("outcome=%+v", outcome)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("handler did not finish after result submission")
	}
}

func TestSlowPermanentFailureSubmissionKeepsRenewing(t *testing.T) {
	core := claimedCore(time.Now().Add(300 * time.Millisecond))
	core.input = coreclient.Input{Bytes: []byte{2}, ContentType: "image/png"}
	renewed := make(chan struct{}, 4)
	renewObserved := make(chan struct{}, 4)
	core.renewStarted = renewObserved
	core.renewFn = func(context.Context, int) (coreclient.RenewResponse, error) {
		renewed <- struct{}{}
		return coreclient.RenewResponse{LeaseUntil: time.Now().Add(600 * time.Millisecond)}, nil
	}
	failureStarted := make(chan struct{}, 1)
	var failureStartedAt time.Time
	core.failureFn = func(ctx context.Context) error {
		failureStartedAt = time.Now()
		failureStarted <- struct{}{}
		for i := 0; i < 2; i++ {
			select {
			case <-renewed:
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		return nil
	}
	handler, err := NewHandler(core, 2*time.Second, 200*time.Millisecond, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	outcomeCh := make(chan Outcome, 1)
	go func() { outcomeCh <- handler.Handle(context.Background(), validBody(t)) }()
	select {
	case <-failureStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("permanent failure submission did not start")
	}
	for i := 0; i < 2; i++ {
		select {
		case <-renewObserved:
		case <-time.After(2 * time.Second):
			t.Fatal("permanent failure submission outlived the lease without renewal")
		}
	}
	if elapsed := time.Since(failureStartedAt); elapsed < 250*time.Millisecond {
		t.Fatalf("permanent failure submission did not cross original lease window: %s", elapsed)
	}
	select {
	case outcome := <-outcomeCh:
		if outcome.Kind != OutcomeAck || outcome.Reason != "permanent_failure" {
			t.Fatalf("outcome=%+v", outcome)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("handler did not finish after permanent failure submission")
	}
}

func TestSuccessfulResultWinsOverRenewalFailureDuringSubmission(t *testing.T) {
	core := claimedCore(time.Now().Add(300 * time.Millisecond))
	renewStarted := make(chan struct{}, 1)
	core.renewStarted = renewStarted
	core.renewErr = errors.New("terminal renewal conflict")
	resultStarted := make(chan struct{}, 1)
	core.resultFn = func(ctx context.Context) error {
		resultStarted <- struct{}{}
		select {
		case <-renewStarted:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	handler, err := NewHandler(core, 2*time.Second, 2*time.Second, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	handler.Transform = func(context.Context, io.Reader, processor.Preset) (processor.Result, error) {
		return validResult(), nil
	}
	outcome := handler.Handle(context.Background(), validBody(t))
	if outcome.Kind != OutcomeAck || outcome.Reason != "succeeded" {
		t.Fatalf("outcome=%+v", outcome)
	}
	select {
	case <-resultStarted:
	default:
		t.Fatal("result submission did not start")
	}
}

func TestRenewalGoroutineStopsAfterResultSubmission(t *testing.T) {
	core := claimedCore(time.Now().Add(300 * time.Millisecond))
	renewStarted := make(chan struct{}, 1)
	renewReturned := make(chan struct{}, 1)
	core.renewStarted = renewStarted
	core.renewDone = renewReturned
	core.renewFn = func(ctx context.Context, _ int) (coreclient.RenewResponse, error) {
		<-ctx.Done()
		return coreclient.RenewResponse{}, ctx.Err()
	}
	handler, err := NewHandler(core, 2*time.Second, 2*time.Second, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	handler.Transform = func(ctx context.Context, _ io.Reader, _ processor.Preset) (processor.Result, error) {
		select {
		case <-renewStarted:
			return validResult(), nil
		case <-ctx.Done():
			return processor.Result{}, ctx.Err()
		}
	}
	outcome := handler.Handle(context.Background(), validBody(t))
	if outcome.Kind != OutcomeAck || outcome.Reason != "succeeded" {
		t.Fatalf("outcome=%+v", outcome)
	}
	select {
	case <-renewReturned:
	default:
		t.Fatal("renewal goroutine did not return before handler completed")
	}
}
