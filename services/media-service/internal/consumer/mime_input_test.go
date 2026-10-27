package consumer

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"reflect"
	"sync/atomic"
	"testing"
	"time"

	"github.com/17356085/MagicAlbum/services/media-service/internal/processor"
)

type mimeInputBaselineCase struct {
	name        string
	contentType string
	wantProcess bool
}

func mimeBaselineResult() processor.Result {
	resultBytes := []byte{2}
	digest := sha256.Sum256(resultBytes)
	return processor.Result{
		Bytes:            resultBytes,
		ContentType:      "image/png",
		Width:            1,
		Height:           1,
		SHA256:           hex.EncodeToString(digest[:]),
		SHA256Bytes:      digest,
		Preset:           processor.PresetPreview,
		ProcessorVersion: processor.CurrentProcessorVersion,
	}
}

func mimeBaselineSnapshot(core *fakeCore) (int, []string) {
	core.mu.Lock()
	defer core.mu.Unlock()
	return core.resultCalls, append([]string(nil), core.failureCodes...)
}

func TestInputMimeBaseline(t *testing.T) {
	cases := []mimeInputBaselineCase{
		{name: "canonical_png", contentType: "image/png", wantProcess: true},
		{name: "uppercase_type_subtype", contentType: "IMAGE/PNG", wantProcess: true},
		{name: "legal_ows_and_quoted_semicolon_parameter", contentType: `image/png ; profile="a;b"`, wantProcess: true},
		{name: "normal_parameter", contentType: "image/png;charset=binary", wantProcess: true},
		{name: "actual_jpeg_is_mismatch", contentType: "image/jpeg", wantProcess: false},
		{name: "empty_is_mismatch", contentType: "", wantProcess: false},
		{name: "malformed_parameter_is_mismatch", contentType: "image/png;broken", wantProcess: false},
	}
	t.Logf("mime baseline table cases=%d expectedValid=%d expectedMismatch=%d", len(cases), 4, 3)

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			core := claimedCore(time.Now().Add(time.Minute))
			core.input.ContentType = tc.contentType
			handler, err := NewHandler(core, 2*time.Second, time.Hour, 10<<20, 10<<20)
			if err != nil {
				t.Fatal(err)
			}
			var transformCalls atomic.Int32
			handler.Transform = func(context.Context, io.Reader, processor.Preset) (processor.Result, error) {
				transformCalls.Add(1)
				return mimeBaselineResult(), nil
			}

			outcome := handler.Handle(context.Background(), validBody(t))
			resultCalls, failureCodes := mimeBaselineSnapshot(core)
			gotTransformCalls := int(transformCalls.Load())
			t.Logf("contentType=%q wantProcess=%t transformCalls=%d resultCalls=%d failureCodes=%q outcome=%+v", tc.contentType, tc.wantProcess, gotTransformCalls, resultCalls, failureCodes, outcome)

			if tc.wantProcess {
				if outcome.Kind != OutcomeAck || outcome.Reason != "succeeded" {
					t.Errorf("valid MIME was not processed: outcome=%+v", outcome)
				}
				if gotTransformCalls != 1 {
					t.Errorf("valid MIME transformCalls=%d, want 1", gotTransformCalls)
				}
				if resultCalls != 1 {
					t.Errorf("valid MIME resultCalls=%d, want 1", resultCalls)
				}
				if len(failureCodes) != 0 {
					t.Errorf("valid MIME failureCodes=%q, want none", failureCodes)
				}
				return
			}

			if outcome.Kind != OutcomeAck || outcome.Reason != "permanent_failure" {
				t.Errorf("negative MIME did not record permanent failure: outcome=%+v", outcome)
			}
			if gotTransformCalls != 0 {
				t.Errorf("negative MIME transformCalls=%d, want 0", gotTransformCalls)
			}
			if resultCalls != 0 {
				t.Errorf("negative MIME resultCalls=%d, want 0", resultCalls)
			}
			if want := []string{"INPUT_MISMATCH"}; !reflect.DeepEqual(failureCodes, want) {
				t.Errorf("negative MIME failureCodes=%q, want %q", failureCodes, want)
			}
		})
	}
}
