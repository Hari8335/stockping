package model

import (
	"errors"
	"github.com/google/uuid"
	"time"
)

type Notification struct {
	EventID             string `json:"eventId"`
	EventType           string `json:"eventType"`
	EventVersion        int    `json:"eventVersion"`
	OccurredAt          string `json:"occurredAt"`
	MerchantID          string `json:"merchantId"`
	NotificationID      string `json:"notificationId"`
	AvailabilityEventID string `json:"availabilityEventId"`
	CorrelationID       string `json:"correlationId"`
}

func (n Notification) Validate() error {
	for _, s := range []string{n.EventID, n.MerchantID, n.NotificationID, n.AvailabilityEventID, n.CorrelationID} {
		id, err := uuid.Parse(s)
		if err != nil || id == uuid.Nil || id.String() != s {
			return errors.New("invalid_notification")
		}
	}
	at, err := time.Parse(time.RFC3339Nano, n.OccurredAt)
	if err != nil || at.After(time.Now().Add(5*time.Minute)) || n.EventType != "notification.requested.v1" || n.EventVersion != 1 || n.EventID != n.NotificationID {
		return errors.New("invalid_notification")
	}
	return nil
}

type Authorization struct {
	Eligible                                            bool
	Reason, Phone, ProductName, SKU, VariantDescription string
}
type NotificationResult struct {
	Status, Reason, ProviderID string
}
