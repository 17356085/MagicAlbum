// Package telemetry provides a deliberately closed JSON event schema. The
// worker never accepts arbitrary log fields, which keeps URLs, tokens and
// message bodies out of logs by construction.
package telemetry

import (
	"encoding/json"
	"io"
	"regexp"
	"sync"
	"time"
)

var idPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var digestPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

type Logger struct {
	output io.Writer
	mutex  sync.Mutex
	clock  func() time.Time
}

func NewLogger(output io.Writer) *Logger {
	if output == nil {
		output = io.Discard
	}
	return &Logger{output: output, clock: time.Now}
}

type Event struct {
	Event     string
	Category  string
	EventID   string
	AssetID   string
	JobID     string
	Digest    string
	Status    string
	ErrorCode string
	Duration  time.Duration
}

type jsonEvent struct {
	Time       string `json:"time"`
	Event      string `json:"event"`
	Category   string `json:"category"`
	EventID    string `json:"eventId,omitempty"`
	AssetID    string `json:"assetId,omitempty"`
	JobID      string `json:"jobId,omitempty"`
	Digest     string `json:"digest,omitempty"`
	Status     string `json:"status,omitempty"`
	ErrorCode  string `json:"errorCode,omitempty"`
	DurationMS int64  `json:"durationMs,omitempty"`
}

func (l *Logger) Event(event Event) {
	if l == nil {
		return
	}
	entry := jsonEvent{Time: l.clock().UTC().Format(time.RFC3339Nano), Event: safeToken(event.Event), Category: safeToken(event.Category)}
	if idPattern.MatchString(event.EventID) {
		entry.EventID = event.EventID
	}
	if idPattern.MatchString(event.AssetID) {
		entry.AssetID = event.AssetID
	}
	if idPattern.MatchString(event.JobID) {
		entry.JobID = event.JobID
	}
	if digestPattern.MatchString(event.Digest) {
		entry.Digest = event.Digest
	}
	entry.Status = safeToken(event.Status)
	entry.ErrorCode = safeToken(event.ErrorCode)
	if event.Duration > 0 {
		entry.DurationMS = event.Duration.Milliseconds()
	}
	if entry.Event == "" {
		entry.Event = "worker"
	}
	if entry.Category == "" {
		entry.Category = "runtime"
	}
	data, err := json.Marshal(entry)
	if err != nil {
		return
	}
	l.mutex.Lock()
	defer l.mutex.Unlock()
	_, _ = l.output.Write(append(data, '\n'))
}

func safeToken(value string) string {
	if len(value) > 64 {
		return ""
	}
	for i := 0; i < len(value); i++ {
		char := value[i]
		if !((char >= 'A' && char <= 'Z') || (char >= 'a' && char <= 'z') ||
			(char >= '0' && char <= '9') || char == '_' || char == '-' || char == '.') {
			return ""
		}
	}
	return value
}
