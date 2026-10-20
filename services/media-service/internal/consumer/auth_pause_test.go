package consumer

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/17356085/MagicAlbum/services/media-service/internal/coreclient"
	"github.com/17356085/MagicAlbum/services/media-service/internal/processor"
	"github.com/17356085/MagicAlbum/services/media-service/internal/telemetry"
)

func authCoreError(operation string) error {
	return &coreclient.Error{
		Operation: operation,
		Status:    http.StatusUnauthorized,
		Kind:      coreclient.ErrorAuth,
		Cause:     errors.New("secret-token-and-body-must-not-be-logged"),
	}
}

func TestAuthErrorsPauseEveryCoreCall(t *testing.T) {
	tests := []struct {
		name  string
		setup func(*fakeCore)
	}{
		{name: "claim", setup: func(core *fakeCore) { core.claimErr = authCoreError("claim") }},
		{name: "input", setup: func(core *fakeCore) { core.inputErr = authCoreError("input") }},
		{name: "renew", setup: func(core *fakeCore) { core.renewErr = authCoreError("renew") }},
		{name: "result", setup: func(core *fakeCore) { core.resultFn = func(context.Context) error { return authCoreError("result") } }},
		{name: "failure", setup: func(core *fakeCore) {
			core.input = coreclient.Input{Bytes: []byte{1}, ContentType: "image/jpeg"}
			core.failureFn = func(context.Context) error { return authCoreError("failure") }
		}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			lease := time.Now().Add(2 * time.Second)
			if tc.name == "renew" {
				lease = time.Now().Add(time.Second)
			}
			core := claimedCore(lease)
			tc.setup(core)
			handler, err := NewHandler(core, 2*time.Second, time.Second, 10<<20, 10<<20)
			if err != nil {
				t.Fatal(err)
			}
			if tc.name == "renew" {
				handler.Transform = func(ctx context.Context, _ io.Reader, _ processor.Preset) (processor.Result, error) {
					<-ctx.Done()
					return processor.Result{}, ctx.Err()
				}
			} else if tc.name == "result" {
				handler.Transform = func(context.Context, io.Reader, processor.Preset) (processor.Result, error) {
					return validResult(), nil
				}
			}
			outcome := handler.Handle(context.Background(), validBody(t))
			if outcome.Kind != OutcomeAuthPaused || outcome.Reason != "core_auth_paused" {
				t.Fatalf("outcome=%+v", outcome)
			}
			wantResults := 0
			if tc.name == "result" {
				wantResults = 1
			}
			if core.resultCount() != wantResults {
				t.Fatalf("result calls=%d, want=%d", core.resultCount(), wantResults)
			}
		})
	}
}

func TestAuthRenewalAfterResultSubmissionDoesNotRollbackSuccess(t *testing.T) {
	core := claimedCore(time.Now().Add(300 * time.Millisecond))
	renewStarted := make(chan struct{}, 1)
	resultStarted := make(chan struct{}, 1)
	core.renewStarted = renewStarted
	core.renewErr = authCoreError("renew")
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
	outcomeCh := make(chan Outcome, 1)
	go func() { outcomeCh <- handler.Handle(context.Background(), validBody(t)) }()
	select {
	case <-resultStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("result submission did not start")
	}
	select {
	case outcome := <-outcomeCh:
		if outcome.Kind != OutcomeAck || outcome.Reason != "succeeded" {
			t.Fatalf("outcome=%+v", outcome)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("handler did not finish after auth renewal race")
	}
	if core.resultCount() != 1 {
		t.Fatalf("result calls=%d", core.resultCount())
	}
}

func TestAuthRenewalDuringFailedResultSubmissionStillPauses(t *testing.T) {
	core := claimedCore(time.Now().Add(300 * time.Millisecond))
	submitReturned := make(chan struct{})
	renewStarted := make(chan struct{}, 1)
	resultStarted := make(chan struct{}, 1)
	core.renewStarted = renewStarted
	core.renewFn = func(ctx context.Context, _ int) (coreclient.RenewResponse, error) {
		// Wait until the final submit has already returned its transport
		// failure, then return the explicit auth failure after cancellation.
		// This makes the ordering deterministic and exercises the deferred
		// renewal join in processClaim.
		select {
		case <-submitReturned:
		case <-ctx.Done():
		}
		<-ctx.Done()
		return coreclient.RenewResponse{}, authCoreError("renew")
	}
	core.resultFn = func(ctx context.Context) error {
		resultStarted <- struct{}{}
		select {
		case <-renewStarted:
		case <-ctx.Done():
			return ctx.Err()
		}
		close(submitReturned)
		return errors.New("temporary result failure")
	}
	handler, err := NewHandler(core, 2*time.Second, 2*time.Second, 10<<20, 10<<20)
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
	select {
	case outcome := <-outcomeCh:
		if outcome.Kind != OutcomeAuthPaused || outcome.Reason != "core_auth_paused" {
			t.Fatalf("outcome=%+v", outcome)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("handler did not finish after failed result submission")
	}
}

type authPauseSession struct {
	deliveries chan Delivery
	mu         sync.Mutex
	ackCount   int
	nackCount  int
	publishCnt int
	closeCnt   int
	closed     chan struct{}
	closeOnce  sync.Once
}

func (s *authPauseSession) Consume(context.Context) (<-chan Delivery, error) {
	return s.deliveries, nil
}

func (s *authPauseSession) Publish(context.Context, Publication) error {
	s.mu.Lock()
	s.publishCnt++
	s.mu.Unlock()
	return nil
}

func (s *authPauseSession) Close() error {
	s.mu.Lock()
	s.closeCnt++
	s.mu.Unlock()
	s.closeOnce.Do(func() { close(s.closed) })
	return nil
}

type authPauseBroker struct {
	mu       sync.Mutex
	connects int
	session  *authPauseSession
}

func (b *authPauseBroker) Connect(context.Context) (Session, error) {
	b.mu.Lock()
	b.connects++
	session := b.session
	b.mu.Unlock()
	return session, nil
}

func TestRuntimeAuthPauseRequeuesWithoutAckOrPublishAndStopsConsuming(t *testing.T) {
	core := &fakeCore{claimErr: authCoreError("claim")}
	handler, err := NewHandler(core, time.Second, time.Second, 10<<20, 10<<20)
	if err != nil {
		t.Fatal(err)
	}
	var logs bytes.Buffer
	session := &authPauseSession{deliveries: make(chan Delivery, 1), closed: make(chan struct{})}
	broker := &authPauseBroker{session: session}
	health := NewHealth()
	runtime := &Runtime{Broker: broker, Handler: handler, Health: health, Logger: telemetry.NewLogger(&logs), RetryPause: time.Millisecond}
	nacked := make(chan bool, 1)
	acked := make(chan struct{}, 1)
	delivery := NewDelivery(validBody(t), func() error {
		acked <- struct{}{}
		return nil
	}, func(requeue bool) error {
		nacked <- requeue
		return nil
	})
	session.deliveries <- delivery

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()
	select {
	case requeue := <-nacked:
		if !requeue {
			t.Fatal("auth pause did not requeue the active delivery")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("auth pause did not nack the active delivery")
	}
	select {
	case <-session.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("auth pause did not close the broker session")
	}

	ready := httptest.NewRecorder()
	health.Handler().ServeHTTP(ready, httptest.NewRequest(http.MethodGet, "/ready", nil))
	if ready.Code != http.StatusServiceUnavailable {
		t.Fatalf("ready status=%d", ready.Code)
	}
	live := httptest.NewRecorder()
	health.Handler().ServeHTTP(live, httptest.NewRequest(http.MethodGet, "/live", nil))
	if live.Code != http.StatusOK {
		t.Fatalf("live status=%d", live.Code)
	}
	if broker.connectCount() != 1 || session.publishCount() != 0 || session.closeCount() != 1 {
		t.Fatalf("connects=%d publishes=%d closes=%d", broker.connectCount(), session.publishCount(), session.closeCount())
	}
	select {
	case <-acked:
		t.Fatal("auth pause acknowledged the active delivery")
	default:
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("runtime returned %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("runtime did not stop after cancellation")
	}
	if broker.connectCount() != 1 {
		t.Fatalf("runtime reconnected after auth pause: %d", broker.connectCount())
	}
	lines := strings.Split(strings.TrimSpace(logs.String()), "\n")
	if len(lines) == 0 || (len(lines) == 1 && lines[0] == "") {
		t.Fatal("auth pause did not emit a fixed-category log")
	}
	for _, line := range lines {
		if line == "" {
			continue
		}
		var event map[string]any
		if err := json.Unmarshal([]byte(line), &event); err != nil {
			t.Fatalf("invalid log line: %v", err)
		}
		if event["category"] != "core_auth_paused" {
			t.Fatalf("unexpected auth pause category: %v", event)
		}
		if strings.Contains(line, "secret-token") || strings.Contains(line, "body") {
			t.Fatalf("auth pause log leaked sensitive data: %s", line)
		}
	}
}

func (b *authPauseBroker) connectCount() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.connects
}

func (s *authPauseSession) publishCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.publishCnt
}

func (s *authPauseSession) closeCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closeCnt
}
