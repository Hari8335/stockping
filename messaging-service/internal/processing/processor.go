package processing

import (
	"context"
	"errors"
	"log/slog"
	"stockping.dev/messaging-service/internal/model"
	"stockping.dev/messaging-service/internal/whatsapp"
	"time"
)

type Store interface {
	Claim(context.Context, model.Message) (string, error)
	Finish(context.Context, string, string, string) error
	BeginOutbound(context.Context, string, model.Message, string) (string, error)
	EndOutbound(context.Context, string, string, string, string, string) error
	Delivery(context.Context, model.Delivery) error
}
type Demand interface {
	Process(context.Context, model.Message) (model.Reply, error)
}
type Provider interface {
	Send(context.Context, string, string, string) (string, error)
}
type Processor struct {
	Store    Store
	Demand   Demand
	Provider Provider
	Logger   *slog.Logger
}

var ErrDatabase = errors.New("database_failure")

func (p *Processor) Process(ctx context.Context, m model.Message) error {
	id, err := p.Store.Claim(ctx, m)
	if err != nil {
		p.Logger.Error("message claim failed", "correlationId", m.CorrelationID, "errorCode", "database_failure")
		return ErrDatabase
	}
	if id == "" {
		p.Logger.Info("duplicate ignored", "correlationId", m.CorrelationID)
		return nil
	}
	state, code := "PROCESSED", ""
	// Persist final state even if the incoming HTTP request was disconnected/cancelled.
	finish := func() error {
		save, cancel := context.WithTimeout(context.WithoutCancel(ctx), 2*time.Second)
		defer cancel()
		if p.Store.Finish(save, id, state, code) != nil {
			p.Logger.Error("message state persistence failed", "correlationId", m.CorrelationID, "errorCode", "database_failure")
			return ErrDatabase
		}
		p.Logger.Info("message processed", "correlationId", m.CorrelationID, "merchantId", m.MerchantID, "status", state, "errorCode", code)
		return nil
	}
	if !m.Supported {
		state = "UNSUPPORTED"
		return finish()
	}
	reply, err := p.Demand.Process(ctx, m)
	if err != nil {
		state = "DEMAND_FAILED"
		code = err.Error()
		return finish()
	}
	if !reply.Send {
		return finish()
	}
	// Consent commands still execute for delayed webhooks; only sending is window-bound.
	if time.Since(m.ReceivedAt) >= 24*time.Hour {
		state = "EXPIRED"
		return finish()
	}
	outID, err := p.Store.BeginOutbound(ctx, id, m, reply.Purpose)
	if err != nil {
		state = "PERSISTENCE_FAILED"
		code = "database_failure"
		_ = finish()
		return ErrDatabase
	}
	providerID, sendErr := p.Provider.Send(ctx, m.Phone, reply.Text, m.CorrelationID)
	outState := "ACCEPTED"
	if sendErr != nil {
		state = "PROVIDER_FAILED"
		outState = "FAILED"
		code = "provider_error"
		var providerErr *whatsapp.SendError
		if errors.As(sendErr, &providerErr) {
			code = providerErr.Code
			if providerErr.Uncertain {
				outState = "UNKNOWN"
			}
		}
	}
	save, cancel := context.WithTimeout(context.WithoutCancel(ctx), 2*time.Second)
	defer cancel()
	if p.Store.EndOutbound(save, outID, m.MerchantID, providerID, outState, code) != nil {
		state = "PERSISTENCE_FAILED"
		code = "database_failure"
		_ = finish()
		return ErrDatabase
	}
	return finish()
}
