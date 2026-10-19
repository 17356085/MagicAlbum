// Package config loads and validates the media worker's process configuration.
package config

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"
)

const (
	MaxJSONBytes           int64 = 64 * 1024
	MaxInputBytes          int64 = 10 * 1024 * 1024
	MaxOutputBytes         int64 = 10 * 1024 * 1024
	DefaultHealthAddr            = ":9091"
	DefaultProcessBudget         = 60 * time.Second
	DefaultConnectTimeout        = 5 * time.Second
	DefaultResponseTimeout       = 15 * time.Second
	DefaultRenewInterval         = 30 * time.Second
	DefaultRetryPause            = 250 * time.Millisecond
)

// Config contains validated values. Secrets are deliberately not exposed by
// String or by the telemetry package.
type Config struct {
	CoreURL              *url.URL
	InternalServiceToken string
	AMQPURL              string
	HealthAddr           string
	ProcessBudget        time.Duration
	HTTPConnectTimeout   time.Duration
	HTTPResponseTimeout  time.Duration
	MaxJSONBytes         int64
	MaxInputBytes        int64
	MaxOutputBytes       int64
	RenewInterval        time.Duration
	RetryPause           time.Duration
}

// Load reads the required environment variables and applies bounded defaults.
func Load() (Config, error) {
	return LoadEnv(os.Getenv)
}

// LoadEnv is injectable for validation tests.
func LoadEnv(get func(string) string) (Config, error) {
	if get == nil {
		return Config{}, errors.New("configuration environment reader is nil")
	}
	core, err := parseCoreURL(get("MEDIA_CORE_URL"))
	if err != nil {
		return Config{}, err
	}
	token, err := validateToken(get("MEDIA_INTERNAL_SERVICE_TOKEN"))
	if err != nil {
		return Config{}, err
	}
	amqpURL := strings.TrimSpace(get("MEDIA_AMQP_URL"))
	if err := validateAMQPURL(amqpURL); err != nil {
		return Config{}, err
	}
	health := strings.TrimSpace(get("MEDIA_HEALTH_ADDR"))
	if health == "" {
		health = DefaultHealthAddr
	}
	if strings.ContainsAny(health, "\r\n") {
		return Config{}, errors.New("MEDIA_HEALTH_ADDR contains invalid characters")
	}
	return Config{
		CoreURL:              core,
		InternalServiceToken: token,
		AMQPURL:              amqpURL,
		HealthAddr:           health,
		ProcessBudget:        DefaultProcessBudget,
		HTTPConnectTimeout:   DefaultConnectTimeout,
		HTTPResponseTimeout:  DefaultResponseTimeout,
		MaxJSONBytes:         MaxJSONBytes,
		MaxInputBytes:        MaxInputBytes,
		MaxOutputBytes:       MaxOutputBytes,
		RenewInterval:        DefaultRenewInterval,
		RetryPause:           DefaultRetryPause,
	}, nil
}

func parseCoreURL(raw string) (*url.URL, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, errors.New("MEDIA_CORE_URL is required")
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return nil, errors.New("MEDIA_CORE_URL must be an absolute HTTP URL")
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return nil, errors.New("MEDIA_CORE_URL must use HTTP or HTTPS")
	}
	if u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("MEDIA_CORE_URL must not contain credentials, query, or fragment")
	}
	if strings.ContainsAny(u.Host, "\r\n") {
		return nil, errors.New("MEDIA_CORE_URL contains invalid host characters")
	}
	// Keep a path prefix, but make joining deterministic and prevent the base
	// path from being interpreted as a file by ResolveReference.
	u.Path = strings.TrimRight(u.Path, "/")
	u.RawPath = ""
	return u, nil
}

func validateToken(token string) (string, error) {
	if len(token) < 32 || len(token) > 256 {
		return "", errors.New("MEDIA_INTERNAL_SERVICE_TOKEN must be 32-256 bytes")
	}
	for i := 0; i < len(token); i++ {
		if token[i] < 0x21 || token[i] > 0x7e {
			return "", errors.New("MEDIA_INTERNAL_SERVICE_TOKEN must use printable ASCII")
		}
	}
	lower := strings.ToLower(token)
	for _, marker := range []string{"changeme", "change-me", "replace", "example", "placeholder", "your-token", "password", "secret", "foobar", "default"} {
		if strings.Contains(lower, marker) {
			return "", errors.New("MEDIA_INTERNAL_SERVICE_TOKEN looks like a placeholder")
		}
	}
	allSame := true
	for i := 1; i < len(token); i++ {
		if token[i] != token[0] {
			allSame = false
			break
		}
	}
	if allSame {
		return "", errors.New("MEDIA_INTERNAL_SERVICE_TOKEN is too predictable")
	}
	return token, nil
}

func validateAMQPURL(raw string) error {
	if raw == "" {
		return errors.New("MEDIA_AMQP_URL is required")
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" || (u.Scheme != "amqp" && u.Scheme != "amqps") {
		return errors.New("MEDIA_AMQP_URL must be an AMQP URL")
	}
	if strings.ContainsAny(raw, "\r\n") {
		return errors.New("MEDIA_AMQP_URL contains invalid characters")
	}
	return nil
}

func (c Config) String() string {
	if c.CoreURL == nil {
		return "media worker configuration (invalid)"
	}
	return fmt.Sprintf("media worker core=%s health=%s budget=%s", c.CoreURL.Redacted(), c.HealthAddr, c.ProcessBudget)
}
