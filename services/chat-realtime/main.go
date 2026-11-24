package main

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	core, err := url.Parse(env("CHAT_REALTIME_CORE_URL", "http://127.0.0.1:8080"))
	if err != nil || core.Host == "" || (core.Scheme != "http" && core.Scheme != "https") || core.User != nil {
		log.Fatal("invalid CHAT_REALTIME_CORE_URL")
	}
	core.Path = "/api/v1/chat/realtime/session"
	core.RawPath, core.RawQuery, core.Fragment = "", "", ""
	core.ForceQuery = false

	origins := make(map[string]bool)
	for origin := range strings.SplitSeq(env("CHAT_REALTIME_ALLOWED_ORIGINS", "http://127.0.0.1:5173,http://localhost:5173"), ",") {
		origins[strings.TrimSpace(origin)] = true
	}
	delete(origins, "")
	if len(origins) == 0 {
		log.Fatal("CHAT_REALTIME_ALLOWED_ORIGINS requires at least one origin")
	}

	clients := &hub{clients: make(map[int64]map[chan struct{}]struct{})}
	go consumeChanges(ctx, brokerURL(), clients)
	coreClient := &http.Client{
		Timeout: 3 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	verify, closeVerifier, err := configuredSessionVerifier(core.String(), coreClient)
	if err != nil {
		log.Fatal(err)
	}
	defer func() {
		if err := closeVerifier(); err != nil {
			log.Printf("close chat realtime session verifier: %v", err)
		}
	}()
	server := &http.Server{
		Addr:              env("CHAT_REALTIME_ADDR", "127.0.0.1:8092"),
		Handler:           realtimeHandler(ctx, clients, origins, verify),
		ReadHeaderTimeout: 5 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	log.Printf("chat realtime listening on %s", server.Addr)
	if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

func brokerURL() string {
	vhost := env("SPRING_RABBITMQ_VIRTUAL_HOST", "/")
	broker := url.URL{
		Scheme:  "amqp",
		User:    url.UserPassword(env("SPRING_RABBITMQ_USERNAME", "guest"), env("SPRING_RABBITMQ_PASSWORD", "guest")),
		Host:    net.JoinHostPort(env("SPRING_RABBITMQ_HOST", "127.0.0.1"), env("SPRING_RABBITMQ_PORT", "5672")),
		Path:    "/" + vhost,
		RawPath: "/" + url.PathEscape(vhost),
	}
	return broker.String()
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
