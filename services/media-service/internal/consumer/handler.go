package consumer

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"strings"
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

func (h *Handler) processClaim(parent context.Context, envelope wire.Envelope, claim coreclient.ClaimResponse) Outcome {
	workCtx, cancelWork := context.WithTimeout(parent, h.ProcessBudget)
	defer cancelWork()
	renewCtx, cancelRenew := context.WithCancel(parent)
	defer cancelRenew()
	renewFailure := make(chan error, 1)
	renewDone := make(chan struct{})
	go h.renewLease(renewCtx, envelope.JobID, claim, cancelWork, renewFailure, renewDone)

	result, outcome := h.execute(workCtx, envelope, claim)
	cancelRenew()
	<-renewDone

	if parent.Err() != nil {
		return Outcome{Kind: OutcomeShutdown, Reason: "shutdown"}
	}
	select {
	case <-renewFailure:
		return Outcome{Kind: OutcomeRetry, Reason: "lease_renewal"}
	default:
	}
	if outcome.Reason == "timeout" {
		_, timeoutOutcome := h.permanentFailure(parent, envelope.JobID, claim, "TIMEOUT")
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
	if err := h.Core.SubmitResult(workCtx, envelope.JobID, claim.JobVersion, claim.LeaseToken, result, claim.InputSHA256); err != nil {
		if parent.Err() != nil {
			return Outcome{Kind: OutcomeShutdown, Reason: "shutdown"}
		}
		return Outcome{Kind: OutcomeRetry, Reason: "result_retry"}
	}
	return Outcome{Kind: OutcomeAck, Reason: "succeeded"}
}

func (h *Handler) renewLease(ctx context.Context, jobID string, claim coreclient.ClaimResponse, cancelWork context.CancelFunc, failure chan<- error, done chan<- struct{}) {
	defer close(done)
	ticker := time.NewTicker(h.RenewInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			renewCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
			_, err := h.Core.Renew(renewCtx, jobID, claim.JobVersion, claim.LeaseToken)
			cancel()
			if err != nil {
				select {
				case failure <- err:
				default:
				}
				cancelWork()
				return
			}
		}
	}
}

func (h *Handler) execute(ctx context.Context, envelope wire.Envelope, claim coreclient.ClaimResponse) (processor.Result, Outcome) {
	input, err := h.Core.GetInput(ctx, envelope.JobID, claim.JobVersion, claim.LeaseToken)
	if err != nil {
		if ctx.Err() != nil {
			return processor.Result{}, h.contextOutcome(ctx)
		}
		return processor.Result{}, Outcome{Kind: OutcomeRetry, Reason: "input_retry"}
	}
	if int64(len(input.Bytes)) != claim.ByteSize || int64(len(input.Bytes)) > h.MaxInputBytes {
		return h.permanentFailure(ctx, envelope.JobID, claim, "INPUT_MISMATCH")
	}
	if strings.SplitN(input.ContentType, ";", 2)[0] != claim.ContentType {
		return h.permanentFailure(ctx, envelope.JobID, claim, "INPUT_MISMATCH")
	}
	digest := sha256.Sum256(input.Bytes)
	if !strings.EqualFold(hex.EncodeToString(digest[:]), claim.InputSHA256) {
		return h.permanentFailure(ctx, envelope.JobID, claim, "INPUT_MISMATCH")
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
				return h.permanentFailure(ctx, envelope.JobID, claim, code)
			}
		}
		return processor.Result{}, Outcome{Kind: OutcomeRetry, Reason: "transform_retry"}
	}
	if result.Preset != processor.Preset(claim.Preset) || result.ProcessorVersion != 1 || result.Width <= 0 || result.Height <= 0 || int64(len(result.Bytes)) == 0 || int64(len(result.Bytes)) > h.MaxOutputBytes {
		return h.permanentFailure(ctx, envelope.JobID, claim, "OUTPUT_TOO_LARGE")
	}
	return result, Outcome{Kind: OutcomeAck}
}

func (h *Handler) permanentFailure(ctx context.Context, jobID string, claim coreclient.ClaimResponse, code string) (processor.Result, Outcome) {
	if ctx.Err() != nil {
		return processor.Result{}, h.contextOutcome(ctx)
	}
	if err := h.Core.SubmitFailure(ctx, jobID, claim.JobVersion, claim.LeaseToken, code); err != nil {
		if ctx.Err() != nil {
			return processor.Result{}, h.contextOutcome(ctx)
		}
		return processor.Result{}, Outcome{Kind: OutcomeRetry, Reason: "failure_retry"}
	}
	return processor.Result{}, Outcome{Kind: OutcomeAck, Reason: "permanent_failure"}
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
