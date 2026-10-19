package config

import (
	"strings"
	"testing"
)

func validEnv() map[string]string {
	return map[string]string{
		"MEDIA_CORE_URL":               "http://127.0.0.1:8080/api",
		"MEDIA_INTERNAL_SERVICE_TOKEN": "0123456789abcdef0123456789abcdef",
		"MEDIA_AMQP_URL":               "amqp://worker:password@127.0.0.1:5672/%2f",
	}
}

func loadMap(values map[string]string) (Config, error) {
	return LoadEnv(func(key string) string { return values[key] })
}

func TestLoadValidatesTrustedEndpointsAndBounds(t *testing.T) {
	cfg, err := loadMap(validEnv())
	if err != nil {
		t.Fatalf("LoadEnv: %v", err)
	}
	if cfg.CoreURL.String() != "http://127.0.0.1:8080/api" || cfg.HealthAddr != DefaultHealthAddr {
		t.Fatalf("unexpected config: %s", cfg.String())
	}
	if cfg.ProcessBudget != DefaultProcessBudget || cfg.MaxJSONBytes != MaxJSONBytes || cfg.MaxInputBytes != MaxInputBytes || cfg.MaxOutputBytes != MaxOutputBytes {
		t.Fatalf("default bounds are wrong: %+v", cfg)
	}
	if strings.Contains(cfg.String(), cfg.InternalServiceToken) {
		t.Fatal("secret appeared in config String")
	}
}

func TestLoadRejectsUnsafeOrPlaceholderConfiguration(t *testing.T) {
	cases := []struct{ name, key, value string }{
		{"core credentials", "MEDIA_CORE_URL", "http://worker:secret@127.0.0.1:8080"},
		{"core query", "MEDIA_CORE_URL", "http://127.0.0.1:8080?token=x"},
		{"core fragment", "MEDIA_CORE_URL", "http://127.0.0.1:8080#fragment"},
		{"token too short", "MEDIA_INTERNAL_SERVICE_TOKEN", "short"},
		{"token placeholder", "MEDIA_INTERNAL_SERVICE_TOKEN", "replace-this-with-a-real-token-0123456789"},
		{"token non-ascii", "MEDIA_INTERNAL_SERVICE_TOKEN", "0123456789abcdef0123456789abcde\u00e9"},
		{"amqp scheme", "MEDIA_AMQP_URL", "https://worker:password@127.0.0.1:5672"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			env := validEnv()
			env[tc.key] = tc.value
			if _, err := loadMap(env); err == nil {
				t.Fatal("expected validation error")
			}
		})
	}
}
