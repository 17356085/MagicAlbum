package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/17356085/MagicAlbum/services/media-service/internal/config"
	"github.com/17356085/MagicAlbum/services/media-service/internal/consumer"
	"github.com/17356085/MagicAlbum/services/media-service/internal/coreclient"
	"github.com/17356085/MagicAlbum/services/media-service/internal/telemetry"
)

var version = "dev"

func main() {
	selfHealth := flag.Bool("self-health", false, "check the local readiness endpoint and exit")
	showVersion := flag.Bool("version", false, "print the worker version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Println(version)
		return
	}
	if *selfHealth {
		if err := checkSelfHealth(os.Getenv("MEDIA_HEALTH_ADDR")); err != nil {
			fmt.Fprintln(os.Stderr, "health check failed")
			os.Exit(1)
		}
		return
	}

	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "invalid worker configuration")
		os.Exit(2)
	}
	core, err := coreclient.New(cfg)
	if err != nil {
		fmt.Fprintln(os.Stderr, "invalid core client configuration")
		os.Exit(2)
	}
	handler, err := consumer.NewHandler(core, cfg.ProcessBudget, cfg.RenewInterval, cfg.MaxInputBytes, cfg.MaxOutputBytes)
	if err != nil {
		fmt.Fprintln(os.Stderr, "invalid worker limits")
		os.Exit(2)
	}
	health := consumer.NewHealth()
	logger := telemetry.NewLogger(os.Stdout)
	factory := consumer.RabbitFactory{URL: cfg.AMQPURL, ConnectTimeout: cfg.HTTPConnectTimeout}
	runtime := &consumer.Runtime{Broker: factory, Handler: handler, Health: health, Logger: logger, RetryPause: cfg.RetryPause}

	server := &http.Server{Addr: cfg.HealthAddr, Handler: health.Handler(), ReadHeaderTimeout: 2 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second}
	serverErr := make(chan error, 1)
	go func() {
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErr <- err
		}
	}()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	runtimeErr := make(chan error, 1)
	go func() { runtimeErr <- runtime.Run(ctx) }()
	drained := false
	select {
	case <-ctx.Done():
	case err := <-serverErr:
		stop()
		_ = err
	case err := <-runtimeErr:
		drained = true
		if err != nil {
			stop()
		}
	}
	health.SetConsuming(false)
	if !drained {
		select {
		case <-runtimeErr:
		case <-time.After(5 * time.Second):
		}
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(shutdownCtx)
}

func checkSelfHealth(rawAddr string) error {
	if strings.TrimSpace(rawAddr) == "" {
		rawAddr = config.DefaultHealthAddr
	}
	host, port, err := net.SplitHostPort(rawAddr)
	if err != nil {
		if strings.HasPrefix(rawAddr, ":") {
			port = strings.TrimPrefix(rawAddr, ":")
			host = "127.0.0.1"
		} else {
			return err
		}
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	client := &http.Client{Timeout: 2 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Get("http://" + net.JoinHostPort(host, port) + "/ready")
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return errors.New("worker is not ready")
	}
	return nil
}
