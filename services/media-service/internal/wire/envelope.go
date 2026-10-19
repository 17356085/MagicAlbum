// Package wire contains the frozen, intentionally small wire contracts used
// by the media worker. It has no transport or application dependencies.
package wire

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
	"time"
)

const (
	MaxEnvelopeBytes int64 = 8192
	SchemaVersion          = 1
	EventType              = "MEDIA_PROCESSING_REQUESTED"
	PresetPreview          = "preview"
	ProcessorVersion       = "1"
)

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var sha256Pattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// Envelope is the complete request message. Adding a field requires a new
// protocol version; unknown JSON fields are rejected.
type Envelope struct {
	SchemaVersion    int       `json:"schemaVersion"`
	EventType        string    `json:"eventType"`
	EventID          string    `json:"eventId"`
	AssetID          string    `json:"assetId"`
	JobID            string    `json:"jobId"`
	JobVersion       int       `json:"jobVersion"`
	Preset           string    `json:"preset"`
	ProcessorVersion string    `json:"processorVersion"`
	OccurredAt       time.Time `json:"occurredAt"`
}

// MarshalJSON rejects malformed envelopes before they are published.
func (e Envelope) MarshalJSON() ([]byte, error) {
	if err := e.Validate(); err != nil {
		return nil, err
	}
	type alias Envelope
	return json.Marshal(alias(e))
}

// Validate checks protocol values and canonical lowercase UUID/time forms.
func (e Envelope) Validate() error {
	if e.SchemaVersion != SchemaVersion || e.EventType != EventType {
		return errors.New("invalid media envelope version or event type")
	}
	if !canonicalUUID(e.EventID) || !canonicalUUID(e.AssetID) || !canonicalUUID(e.JobID) {
		return errors.New("invalid media envelope identifier")
	}
	if e.JobVersion < 1 || e.Preset != PresetPreview || e.ProcessorVersion != ProcessorVersion {
		return errors.New("invalid media envelope binding")
	}
	if e.OccurredAt.IsZero() || e.OccurredAt.Location() != time.UTC {
		return errors.New("media envelope time must be UTC")
	}
	return nil
}

// ParseEnvelope enforces the 8 KiB body limit, exact field set, and no
// trailing JSON values. It does not return input body text in any error.
func ParseEnvelope(body []byte) (Envelope, error) {
	var envelope Envelope
	if len(body) == 0 || int64(len(body)) > MaxEnvelopeBytes {
		return envelope, errors.New("invalid media envelope size")
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&envelope); err != nil {
		return Envelope{}, errors.New("invalid media envelope JSON")
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return Envelope{}, errors.New("invalid media envelope trailing data")
	}
	if err := envelope.Validate(); err != nil {
		return Envelope{}, err
	}
	// JSON time.Time accepts several forms. Require the canonical UTC spelling
	// used by the producer so offsets never alter the signed/event identity.
	var raw struct {
		OccurredAt string `json:"occurredAt"`
	}
	if err := json.Unmarshal(body, &raw); err != nil || !canonicalUTCTime(raw.OccurredAt, envelope.OccurredAt) {
		return Envelope{}, errors.New("invalid media envelope timestamp")
	}
	return envelope, nil
}

func canonicalUUID(value string) bool { return uuidPattern.MatchString(value) }

func canonicalUTCTime(raw string, parsed time.Time) bool {
	if raw == "" || !strings.HasSuffix(raw, "Z") || parsed.Location() != time.UTC {
		return false
	}
	canonical, err := time.Parse(time.RFC3339Nano, raw)
	if err != nil {
		return false
	}
	return canonical.Equal(parsed)
}

// QuarantineMessage intentionally carries only a digest and a fixed reason.
type QuarantineMessage struct {
	Digest string `json:"digest"`
	Reason string `json:"reason"`
}

func NewQuarantineMessage(body []byte, reason string) QuarantineMessage {
	digest := sha256.Sum256(body)
	return QuarantineMessage{Digest: hex.EncodeToString(digest[:]), Reason: reason}
}

func (q QuarantineMessage) Validate() error {
	if !sha256Pattern.MatchString(q.Digest) || q.Reason == "" || strings.ContainsAny(q.Reason, " \t\r\n") {
		return errors.New("invalid quarantine message")
	}
	return nil
}

func (q QuarantineMessage) MarshalJSON() ([]byte, error) {
	if err := q.Validate(); err != nil {
		return nil, err
	}
	type alias QuarantineMessage
	return json.Marshal(alias(q))
}

func (e Envelope) String() string {
	return fmt.Sprintf("media envelope event=%s asset=%s job=%s", e.EventID, e.AssetID, e.JobID)
}
