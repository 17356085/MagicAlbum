package wire

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func fixtureEnvelope() Envelope {
	return Envelope{SchemaVersion: 1, EventType: EventType,
		EventID:    "01234567-89ab-cdef-0123-456789abcdef",
		AssetID:    "11234567-89ab-cdef-0123-456789abcdef",
		JobID:      "21234567-89ab-cdef-0123-456789abcdef",
		JobVersion: 1, Preset: PresetPreview, ProcessorVersion: ProcessorVersion,
		OccurredAt: time.Date(2026, 10, 19, 11, 22, 33, 123000000, time.UTC)}
}

func TestParseEnvelopeIsStrictAndCanonical(t *testing.T) {
	body, err := json.Marshal(fixtureEnvelope())
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := ParseEnvelope(body)
	if err != nil {
		t.Fatalf("ParseEnvelope: %v", err)
	}
	if parsed.JobID != fixtureEnvelope().JobID || !bytes.Equal(body, mustMarshal(t, parsed)) {
		t.Fatal("round trip changed envelope")
	}
	// Java Instant uses millisecond groups; Go's formatter trims trailing zeroes.
	javaTimestamp := strings.Replace(string(body), ".123Z", ".100Z", 1)
	if _, err := ParseEnvelope([]byte(javaTimestamp)); err != nil {
		t.Fatalf("rejected Java UTC timestamp: %v", err)
	}
	for _, invalid := range []string{
		`{"schemaVersion":1,"eventType":"MEDIA_PROCESSING_REQUESTED","eventId":"01234567-89ab-cdef-0123-456789abcdef","assetId":"11234567-89ab-cdef-0123-456789abcdef","jobId":"21234567-89ab-cdef-0123-456789abcdef","jobVersion":1,"preset":"preview","processorVersion":"1","occurredAt":"2026-10-19T11:22:33+00:00"}`,
		`{"schemaVersion":1,"eventType":"MEDIA_PROCESSING_REQUESTED","eventId":"01234567-89ab-cdef-0123-456789abcdef","assetId":"11234567-89ab-cdef-0123-456789abcdef","jobId":"21234567-89ab-cdef-0123-456789abcdef","jobVersion":1,"preset":"preview","processorVersion":"1","occurredAt":"2026-10-19T11:22:33Z","extra":true}`,
		string(body) + ` {}`,
	} {
		if _, err := ParseEnvelope([]byte(invalid)); err == nil {
			t.Fatalf("accepted invalid envelope: %s", invalid)
		}
	}
	if _, err := ParseEnvelope([]byte(strings.Repeat("x", int(MaxEnvelopeBytes)+1))); err == nil {
		t.Fatal("accepted oversized envelope")
	}
}

func TestQuarantineMessageContainsDigestAndReasonOnly(t *testing.T) {
	message := NewQuarantineMessage([]byte("secret body"), "invalid_message")
	body, err := json.Marshal(message)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(body, []byte("secret body")) || !strings.Contains(string(body), `"digest"`) || !strings.Contains(string(body), `"reason"`) {
		t.Fatalf("unexpected quarantine body: %s", body)
	}
	if err := message.Validate(); err != nil {
		t.Fatal(err)
	}
}

func mustMarshal(t *testing.T, value Envelope) []byte {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return data
}
