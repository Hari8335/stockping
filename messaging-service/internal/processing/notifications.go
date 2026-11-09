package processing

import (
	"context"
	"errors"
	"regexp"
	"stockping.dev/messaging-service/internal/config"
	"stockping.dev/messaging-service/internal/model"
	"stockping.dev/messaging-service/internal/whatsapp"
	"time"
)

type NotificationStore interface {
	ReceiveNotification(context.Context, model.Notification) (model.NotificationResult, error)
	BeginNotification(context.Context, model.Notification, string) (string, error)
	FinishNotification(context.Context, model.Notification, model.NotificationResult) error
	RecoverNotification(context.Context, model.Notification) error
	NotificationReported(context.Context, string) error
}
type NotificationDemand interface {
	Authorize(context.Context, model.Notification) (model.Authorization, error)
	Complete(context.Context, model.Notification, model.NotificationResult) error
}
type TemplateProvider interface {
	SendTemplate(context.Context, string, string, string, []string, string) (string, error)
}
type Notifications struct {
	Store         NotificationStore
	Demand        NotificationDemand
	Provider      TemplateProvider
	Configuration config.Notifications
	MerchantID    string
}

func (p *Notifications) Process(ctx context.Context, n model.Notification) error {
	result, err := p.Store.ReceiveNotification(ctx, n)
	if err != nil {
		return err
	}
	// A redelivery after a crash waits beyond the maximum provider deadline before recovering the claim.
	deadline := time.Now().Add(35 * time.Second)
	for result.Status == "SENDING" && time.Now().Before(deadline) {
		if err = p.Store.RecoverNotification(ctx, n); err != nil {
			return err
		}
		result, err = p.Store.ReceiveNotification(ctx, n)
		if err != nil {
			return err
		}
		if result.Status != "SENDING" {
			break
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
	if result.Status == "SENDING" {
		return errors.New("send_in_progress")
	}
	if result.Status == "RECEIVED" {
		authorization := model.Authorization{}
		if n.MerchantID != p.MerchantID {
			authorization.Reason = "channel_unmapped"
		} else {
			authorization, err = p.Demand.Authorize(ctx, n)
			if err != nil {
				return err
			}
		}
		if !authorization.Eligible {
			result = model.NotificationResult{Status: "SKIPPED", Reason: authorization.Reason}
		} else if !p.Configuration.Allows(authorization.Phone) {
			result = model.NotificationResult{Status: "SKIPPED", Reason: "recipient_not_authorized"}
		} else {
			if !regexp.MustCompile(`^\+[1-9][0-9]{7,14}$`).MatchString(authorization.Phone) || authorization.ProductName == "" || authorization.SKU == "" {
				return errors.New("invalid_authorization")
			}
			values := map[string]string{"product_name": authorization.ProductName, "sku": authorization.SKU, "variant_description": authorization.VariantDescription}
			parameters := make([]string, 0, len(p.Configuration.Parameters))
			for _, key := range p.Configuration.Parameters {
				parameters = append(parameters, values[key])
			}
			id, err := p.Store.BeginNotification(ctx, n, authorization.Phone)
			if err != nil {
				return err
			}
			if id == "" {
				return errors.New("send_in_progress")
			}
			providerID, err := p.Provider.SendTemplate(ctx, authorization.Phone, p.Configuration.Template, p.Configuration.Language, parameters, n.CorrelationID)
			result = model.NotificationResult{Status: "ACCEPTED", ProviderID: providerID}
			if err != nil {
				result = model.NotificationResult{Status: "UNKNOWN", Reason: "provider_error"}
				var sendError *whatsapp.SendError
				if errors.As(err, &sendError) {
					result.Reason = sendError.Code
					if !sendError.Uncertain {
						result.Status = "FAILED"
					}
				}
			}
		}
		save, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		err = p.Store.FinishNotification(save, n, result)
		cancel()
		if err != nil {
			return err
		}
	}
	if err = p.Demand.Complete(ctx, n, result); err != nil {
		return err
	}
	return p.Store.NotificationReported(ctx, n.NotificationID)
}
