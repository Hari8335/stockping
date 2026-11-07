package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"stockping.dev/messaging-service/internal/config"
	demandgrpc "stockping.dev/messaging-service/internal/grpc"
	httpapi "stockping.dev/messaging-service/internal/http"
	"stockping.dev/messaging-service/internal/processing"
	"stockping.dev/messaging-service/internal/store"
	"stockping.dev/messaging-service/internal/whatsapp"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(logger); err != nil {
		logger.Error("messaging service stopped", "error", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	port, err := config.Port(os.Getenv("PORT"))
	if err != nil {
		return err
	}
	settings, err := config.Load(os.Getenv)
	if err != nil {
		return err
	}
	startup, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	database, err := store.Open(startup, settings.DatabaseURL)
	if err != nil {
		return err
	}
	defer database.DB.Close()
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		ping, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		if database.DB.PingContext(ping) != nil {
			http.Error(w, "database_unavailable", 503)
			return
		}
		httpapi.NewHandler().ServeHTTP(w, r)
	})
	if settings.Enabled {
		demand, err := demandgrpc.New(settings.DemandTarget, settings.DemandToken, settings.DemandTimeout, settings.DemandTLS)
		if err != nil {
			return err
		}
		defer demand.Close()
		provider := whatsapp.NewClient(settings.BaseURL, settings.Version, settings.PhoneID, settings.AccessToken, settings.ProviderTimeout)
		processor := &processing.Processor{Store: database, Demand: demand, Provider: provider, Logger: logger}
		mux.Handle("/webhooks/whatsapp", &httpapi.Webhook{VerifyToken: settings.VerifyToken, AppSecret: settings.AppSecret, Channels: whatsapp.SingleChannel{WABA: settings.WABA, PhoneID: settings.PhoneID, MerchantID: settings.MerchantID}, Processor: processor})
	}
	logger.Info("provider integration configured", "enabled", settings.Enabled)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	server := &http.Server{
		Addr: ":" + port, Handler: mux,
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second,
	}
	errorsCh := make(chan error, 1)
	go func() { errorsCh <- server.ListenAndServe() }()
	logger.Info("messaging service starting", "port", port)
	select {
	case err := <-errorsCh:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return server.Shutdown(shutdownCtx)
	}
}
