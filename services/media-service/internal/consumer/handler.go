package consumer

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"strings"
	"sync"
	"time"

	"github.com/17356085/MagicAlbum/services/media-service/internal/coreclient"
	"github.com/17356085/MagicAlbum/services/media-service/internal/processor"
	"github.com/17356085/MagicAlbum/services/media-service/internal/wire"
)

type OutcomeKind uint8

const (
	OutcomeAck OutcomeKind = iota + 1
	OutcomeRetry
	OutcomeQuarantine
	OutcomeShutdown
	OutcomeAuthPaused
)

type Outcome struct {
	Kind   OutcomeKind
	Reason string
}

type Core interface {
	Claim(context.Context, wire.Envelope) (coreclient.ClaimResponse, error)
	Renew(context.Context, string, int, string) (coreclient.RenewResponse, error)
	GetInput(context.Context, string, int, string) (coreclient.Input, error)
	SubmitResult(context.Context, string, int, string, processor.Result, string) error
	SubmitFailure(context.Context, string, int, string, string) error
}

type TransformFunc func(context.Context, io.Reader, processor.Preset) (processor.Result, error)

type Handler struct {
	Core           Core
	Transform      TransformFunc
	ProcessBudget  time.Duration
	RenewInterval  time.Duration
	MaxInputBytes  int64
	MaxOutputBytes int64
	Now            func() time.Time
}

type leasePhase uint8

const (
	leaseProcessing leasePhase = iota + 1
	leaseSubmitting
	leaseCompleted
)

// leaseState closes the race between a renewal failure and a final submit. A
// failure observed before submission prevents the submit; a failure observed
// after submission has started cannot invalidate an already authoritative
// successful response.
type leaseState struct {
	mu         sync.Mutex
	phase      leasePhase
	failed     bool
	authPaused bool
}

func newLeaseState() *leaseState {
	return &leaseState{phase: leaseProcessing}
}

func (s *leaseState) beginSubmission() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.phase != leaseProcessing || s.failed {
		return false
	}
	s.phase = leaseSubmitting
	return true
}

func (s *leaseState) finishSubmission(success bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if success {
		s.phase = leaseCompleted
	}
}

func (s *leaseState) renewalFailed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.phase != leaseProcessing {
		return false
	}
	s.failed = true
	return true
}

func (s *leaseState) authFailed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.phase == leaseCompleted {
		return false
	}
	s.failed = true
	s.authPaused = true
	return s.phase == leaseProcessing
}

func (s *leaseState) hasRenewalFailure() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.failed
}

func (s *leaseState) hasAuthFailure() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.authPaused
}

func NewHandler(core Core, processBudget, renewInterval time.Duration, maxInput, maxOutput int64) (*Handler, error) {
	if core == nil {
		return nil, errors.New("media handler requires a core client")
	}
	if processBudget <= 0 {
		return nil, errors.New("media handler process budget must be positive")
	}
	if renewInterval <= 0 {
		return nil, errors.New("media handler renew interval must be positive")
	}
	if maxInput <= 0 || maxOutput <= 0 {
		return nil, errors.New("media handler byte limits must be positive")
	}
	return &Handler{Core: core, Transform: processor.Transform, ProcessBudget: processBudget, RenewInterval: renewInterval, MaxInputBytes: maxInput, MaxOutputBytes: maxOutput, Now: time.Now}, nil
}

func (h *Handler) Handle(ctx context.Context, body []byte) Outcome {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return Outcome{Kind: OutcomeShutdown, Reason: "shutdown"}
	}
	envelope, err := wire.ParseEnvelope(body)
	if err != nil {
		return Outcome{Kind: OutcomeQuarantine, Reason: "invalid_message"}
	}
	claim, err := h.Core.Claim(ctx, envelope)
	if err != nil {
		if coreclient.IsKind(err, coreclient.ErrorAuth) {
			return Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
		}
		if coreclient.IsKind(err, coreclient.ErrorInvalid) {
			return Outcome{Kind: OutcomeQuarantine, Reason: "claim_invalid"}
		}
		if ctx.Err() != nil {
			return Outcome{Kind: OutcomeShutdown, Reason: "shutdown"}
		}
		return Outcome{Kind: OutcomeRetry, Reason: "claim_retry"}
	}
	switch claim.Status {
	case "SUCCEEDED", "FAILED", "CANCELLED":
		return Outcome{Kind: OutcomeAck, Reason: "terminal"}
	case "CLAIMED":
		return h.processClaim(ctx, envelope, claim)
	default:
		return Outcome{Kind: OutcomeQuarantine, Reason: "claim_invalid"}
	}
}

func (h *Handler) processClaim(parent context.Context, envelope wire.Envelope, claim coreclient.ClaimResponse) (outcome Outcome) {
	if _, ok := h.renewalDelay(h.now(), claim.LeaseUntil); !ok {
		return Outcome{Kind: OutcomeRetry, Reason: "lease_renewal"}
	}
	workCtx, cancelWork := context.WithTimeout(parent, h.ProcessBudget)
	defer cancelWork()
	renewCtx, cancelRenew := context.WithCancel(parent)
	renewDone := make(chan struct{})
	lease := newLeaseState()
	go h.renewLease(renewCtx, envelope.JobID, claim, cancelWork, lease, renewDone)

	defer func() {
		cancelRenew()
		<-renewDone
		if parent.Err() == nil && outcome.Kind != OutcomeAck && lease.hasAuthFailure() {
			outcome = Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
		}
	}()

	result, outcome := h.execute(workCtx, envelope, claim, lease)

	if parent.Err() != nil {
		return Outcome{Kind: OutcomeShutdown, Reason: "shutdown"}
	}
	if outcome.Kind != OutcomeAck && lease.hasAuthFailure() {
		return Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
	}
	if outcome.Kind != OutcomeAck && lease.hasRenewalFailure() {
		return Outcome{Kind: OutcomeRetry, Reason: "lease_renewal"}
	}
	if outcome.Reason == "timeout" {
		_, timeoutOutcome := h.permanentFailure(parent, envelope.JobID, claim, "TIMEOUT", lease)
		return timeoutOutcome
	}
	if outcome.Kind != OutcomeAck {
		return outcome
	}
	if outcome.Reason == "permanent_failure" {
		return outcome
	}
	if result.Bytes == nil {
		return Outcome{Kind: OutcomeRetry, Reason: "missing_result"}
	}
	if !lease.beginSubmission() {
		if lease.hasAuthFailure() {
			return Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
		}
		return Outcome{Kind: OutcomeRetry, Reason: "lease_renewal"}
	}
	if err := h.Core.SubmitResult(workCtx, envelope.JobID, claim.JobVersion, claim.LeaseToken, result, claim.InputSHA256); err != nil {
		lease.finishSubmission(false)
		if coreclient.IsKind(err, coreclient.ErrorAuth) {
			return Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
		}
		if lease.hasAuthFailure() {
			return Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
		}
		if parent.Err() != nil {
			return Outcome{Kind: OutcomeShutdown, Reason: "shutdown"}
		}
		return Outcome{Kind: OutcomeRetry, Reason: "result_retry"}
	}
	lease.finishSubmission(true)
	return Outcome{Kind: OutcomeAck, Reason: "succeeded"}
}

func (h *Handler) renewLease(ctx context.Context, jobID string, claim coreclient.ClaimResponse, cancelWork context.CancelFunc, lease *leaseState, done chan<- struct{}) {
	defer close(done)
	delay, ok := h.renewalDelay(h.now(), claim.LeaseUntil)
	if !ok {
		h.failRenewal(lease, cancelWork)
		return
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	leaseUntil := claim.LeaseUntil
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			now := h.now()
			remaining := leaseUntil.Sub(now)
			if remaining <= 0 {
				h.failRenewal(lease, cancelWork)
				return
			}
			timeout := remaining
			if timeout > 15*time.Second {
				timeout = 15 * time.Second
			}
			renewCtx, cancel := context.WithTimeout(ctx, timeout)
			renewed, err := h.Core.Renew(renewCtx, jobID, claim.JobVersion, claim.LeaseToken)
			requestErr := renewCtx.Err()
			cancel()
			if err != nil {
				// An explicit auth response is a configuration failure even when
				// cancellation raced the response. Record it before joining the
				// renewal goroutine so a failed submit cannot hide the pause.
				if coreclient.IsKind(err, coreclient.ErrorAuth) {
					h.failAuth(lease, cancelWork)
				} else if ctx.Err() == nil {
					h.failRenewal(lease, cancelWork)
				}
				return
			}
			if requestErr != nil {
				if ctx.Err() == nil {
					h.failRenewal(lease, cancelWork)
				}
				return
			}
			if ctx.Err() != nil {
				return
			}
			now = h.now()
			if renewed.LeaseUntil.IsZero() || !renewed.LeaseUntil.After(now) {
				h.failRenewal(lease, cancelWork)
				return
			}
			leaseUntil = renewed.LeaseUntil
			delay, ok = h.renewalDelay(now, leaseUntil)
			if !ok {
				h.failRenewal(lease, cancelWork)
				return
			}
			timer.Reset(delay)
		}
	}
}

func (h *Handler) failRenewal(lease *leaseState, cancelWork context.CancelFunc) {
	if lease.renewalFailed() {
		cancelWork()
	}
}

func (h *Handler) failAuth(lease *leaseState, cancelWork context.CancelFunc) {
	if lease.authFailed() {
		cancelWork()
	}
}

func (h *Handler) execute(ctx context.Context, envelope wire.Envelope, claim coreclient.ClaimResponse, lease *leaseState) (processor.Result, Outcome) {
	input, err := h.Core.GetInput(ctx, envelope.JobID, claim.JobVersion, claim.LeaseToken)
	if err != nil {
		if coreclient.IsKind(err, coreclient.ErrorAuth) {
			return processor.Result{}, Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
		}
		if ctx.Err() != nil {
			return processor.Result{}, h.contextOutcome(ctx)
		}
		return processor.Result{}, Outcome{Kind: OutcomeRetry, Reason: "input_retry"}
	}
	if int64(len(input.Bytes)) != claim.ByteSize || int64(len(input.Bytes)) > h.MaxInputBytes {
		return h.permanentFailure(ctx, envelope.JobID, claim, "INPUT_MISMATCH", lease)
	}
	if strings.SplitN(input.ContentType, ";", 2)[0] != claim.ContentType {
		return h.permanentFailure(ctx, envelope.JobID, claim, "INPUT_MISMATCH", lease)
	}
	digest := sha256.Sum256(input.Bytes)
	if !strings.EqualFold(hex.EncodeToString(digest[:]), claim.InputSHA256) {
		return h.permanentFailure(ctx, envelope.JobID, claim, "INPUT_MISMATCH", lease)
	}
	if err := ctx.Err(); err != nil {
		return processor.Result{}, h.contextOutcome(ctx)
	}
	transform := h.Transform
	if transform == nil {
		transform = processor.Transform
	}
	result, err := transform(ctx, bytes.NewReader(input.Bytes), processor.Preset(claim.Preset))
	if err != nil {
		if ctx.Err() != nil {
			return processor.Result{}, h.contextOutcome(ctx)
		}
		var processorErr *processor.Error
		if errors.As(err, &processorErr) {
			code, ok := permanentCode(processorErr.Kind)
			if ok {
				return h.permanentFailure(ctx, envelope.JobID, claim, code, lease)
			}
		}
		return processor.Result{}, Outcome{Kind: OutcomeRetry, Reason: "transform_retry"}
	}
	if result.Preset != processor.Preset(claim.Preset) || result.ProcessorVersion != 1 || result.Width <= 0 || result.Height <= 0 || int64(len(result.Bytes)) == 0 || int64(len(result.Bytes)) > h.MaxOutputBytes {
		return h.permanentFailure(ctx, envelope.JobID, claim, "OUTPUT_TOO_LARGE", lease)
	}
	return result, Outcome{Kind: OutcomeAck}
}

func (h *Handler) permanentFailure(ctx context.Context, jobID string, claim coreclient.ClaimResponse, code string, lease *leaseState) (processor.Result, Outcome) {
	if ctx.Err() != nil {
		return processor.Result{}, h.contextOutcome(ctx)
	}
	if lease != nil && !lease.beginSubmission() {
		if lease.hasAuthFailure() {
			return processor.Result{}, Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
		}
		return processor.Result{}, Outcome{Kind: OutcomeRetry, Reason: "lease_renewal"}
	}
	if err := h.Core.SubmitFailure(ctx, jobID, claim.JobVersion, claim.LeaseToken, code); err != nil {
		if lease != nil {
			lease.finishSubmission(false)
		}
		if coreclient.IsKind(err, coreclient.ErrorAuth) {
			return processor.Result{}, Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
		}
		if lease != nil && lease.hasAuthFailure() {
			return processor.Result{}, Outcome{Kind: OutcomeAuthPaused, Reason: "core_auth_paused"}
		}
		if ctx.Err() != nil {
			return processor.Result{}, h.contextOutcome(ctx)
		}
		return processor.Result{}, Outcome{Kind: OutcomeRetry, Reason: "failure_retry"}
	}
	if lease != nil {
		lease.finishSubmission(true)
	}
	return processor.Result{}, Outcome{Kind: OutcomeAck, Reason: "permanent_failure"}
}

func (h *Handler) now() time.Time {
	if h.Now != nil {
		return h.Now()
	}
	return time.Now()
}

func (h *Handler) renewalDelay(now, leaseUntil time.Time) (time.Duration, bool) {
	if leaseUntil.IsZero() || h.RenewInterval <= 0 {
		return 0, false
	}
	remaining := leaseUntil.Sub(now)
	if remaining <= 0 {
		return 0, false
	}
	delay := remaining / 3
	if delay <= 0 {
		delay = time.Nanosecond
	}
	if delay > h.RenewInterval {
		delay = h.RenewInterval
	}
	return delay, true
}

func (h *Handler) contextOutcome(ctx context.Context) Outcome {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return Outcome{Kind: OutcomeRetry, Reason: "timeout"}
	}
	return Outcome{Kind: OutcomeRetry, Reason: "processing_cancelled"}
}

func permanentCode(kind processor.ErrorKind) (string, bool) {
	switch kind {
	case processor.ErrorKindUnsupportedFormat:
		return "UNSUPPORTED_FORMAT", true
	case processor.ErrorKindAnimatedImage:
		return "ANIMATED_IMAGE", true
	case processor.ErrorKindMalformedImage:
		return "MALFORMED_IMAGE", true
	case processor.ErrorKindInvalidMetadata:
		return "INVALID_METADATA", true
	case processor.ErrorKindImageLimits:
		return "IMAGE_LIMITS", true
	case processor.ErrorKindInputTooLarge:
		return "INPUT_TOO_LARGE", true
	case processor.ErrorKindOutputTooLarge:
		return "OUTPUT_TOO_LARGE", true
	case processor.ErrorKindEncode:
		return "ENCODE", true
	default:
		return "", false
	}
}
