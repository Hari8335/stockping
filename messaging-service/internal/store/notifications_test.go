//go:build integration

package store_test

import (
	"context"
	"errors"
	"github.com/google/uuid"
	"stockping.dev/messaging-service/internal/config"
	"stockping.dev/messaging-service/internal/model"
	"stockping.dev/messaging-service/internal/processing"
	"stockping.dev/messaging-service/internal/testsupport"
	"stockping.dev/messaging-service/internal/whatsapp"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type notificationDemand struct {
	eligible    bool
	reports     atomic.Int32
	unavailable bool
}

func (d *notificationDemand) Authorize(context.Context, model.Notification) (model.Authorization, error) {
	if d.unavailable {
		return model.Authorization{}, errors.New("unavailable")
	}
	return model.Authorization{Eligible: d.eligible, Reason: "consent_or_account", Phone: "+94770000000", ProductName: "Product", SKU: "SKU"}, nil
}
func (d *notificationDemand) Complete(context.Context, model.Notification, model.NotificationResult) error {
	d.reports.Add(1)
	return nil
}

type templateProvider struct {
	sends   atomic.Int32
	failure error
}

func (p *templateProvider) SendTemplate(context.Context, string, string, string, []string, string) (string, error) {
	p.sends.Add(1)
	if p.failure != nil {
		return "", p.failure
	}
	return "synthetic.provider." + uuid.NewString(), nil
}
func notification() model.Notification {
	id := uuid.NewString()
	return model.Notification{EventID: id, NotificationID: id, MerchantID: uuid.NewString(), AvailabilityEventID: uuid.NewString(), CorrelationID: uuid.NewString(), EventType: "notification.requested.v1", EventVersion: 1, OccurredAt: time.Now().Format(time.RFC3339)}
}
func TestNotificationConcurrentClaimsAndReplaySendOnce(t *testing.T) {
	db := testsupport.Database(t)
	n := notification()
	ctx := context.Background()
	d := &notificationDemand{eligible: true}
	provider := &templateProvider{}
	processor := &processing.Notifications{Store: db, Demand: d, Provider: provider, MerchantID: n.MerchantID, Configuration: config.Notifications{TestEndpoint: true, Parameters: []string{"product_name", "sku"}}}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Go(func() { _ = processor.Process(ctx, n) })
	}
	wg.Wait()
	if err := processor.Process(ctx, n); err != nil {
		t.Fatal(err)
	}
	if provider.sends.Load() != 1 {
		t.Fatal("duplicate external send")
	}
	r, err := db.ReceiveNotification(ctx, n)
	if err != nil || r.Status != "ACCEPTED" || r.ProviderID == "" {
		t.Fatal("missing accepted outcome")
	}
	var count int
	if db.DB.QueryRow("SELECT count(*) FROM message_records WHERE notification_id=$1", n.NotificationID).Scan(&count) != nil || count != 1 {
		t.Fatal("duplicate persistence")
	}
	if err = db.Delivery(ctx, model.Delivery{MerchantID: n.MerchantID, ProviderID: r.ProviderID, Status: "read", Time: time.Now()}); err != nil {
		t.Fatal(err)
	}
	var status string
	_ = db.DB.QueryRow("SELECT status FROM message_records WHERE notification_id=$1", n.NotificationID).Scan(&status)
	if status != "READ" {
		t.Fatal("template delivery not reconciled")
	}
}
func TestNotificationFailureAndUncertaintyNeverResend(t *testing.T) {
	for _, tc := range []struct {
		uncertain bool
		status    string
	}{{false, "FAILED"}, {true, "UNKNOWN"}} {
		t.Run(tc.status, func(t *testing.T) {
			db := testsupport.Database(t)
			n := notification()
			ctx := context.Background()
			provider := &templateProvider{failure: &whatsapp.SendError{Code: "provider_failure", Uncertain: tc.uncertain}}
			processor := &processing.Notifications{Store: db, Demand: &notificationDemand{eligible: true}, Provider: provider, MerchantID: n.MerchantID, Configuration: config.Notifications{TestEndpoint: true}}
			for i := 0; i < 2; i++ {
				if err := processor.Process(ctx, n); err != nil {
					t.Fatal(err)
				}
			}
			r, _ := db.ReceiveNotification(ctx, n)
			if r.Status != tc.status || provider.sends.Load() != 1 {
				t.Fatal("unsafe provider replay")
			}
		})
	}
}
func TestNotificationConsentAndChannelSuppressSending(t *testing.T) {
	db := testsupport.Database(t)
	n := notification()
	ctx := context.Background()
	provider := &templateProvider{}
	processor := &processing.Notifications{Store: db, Demand: &notificationDemand{}, Provider: provider, MerchantID: n.MerchantID, Configuration: config.Notifications{TestEndpoint: true}}
	if err := processor.Process(ctx, n); err != nil {
		t.Fatal(err)
	}
	processor.Demand = &notificationDemand{eligible: true}
	n = notification()
	if err := processor.Process(ctx, n); err != nil {
		t.Fatal(err)
	}
	if provider.sends.Load() != 0 {
		t.Fatal("ineligible sent")
	}
}
func TestNotificationInternalFailureRecoversBeforeClaim(t *testing.T) {
	db := testsupport.Database(t)
	n := notification()
	ctx := context.Background()
	provider := &templateProvider{}
	d := &notificationDemand{eligible: true, unavailable: true}
	processor := &processing.Notifications{Store: db, Demand: d, Provider: provider, MerchantID: n.MerchantID, Configuration: config.Notifications{TestEndpoint: true}}
	if processor.Process(ctx, n) == nil {
		t.Fatal("expected retryable failure")
	}
	d.unavailable = false
	if err := processor.Process(ctx, n); err != nil {
		t.Fatal(err)
	}
	if provider.sends.Load() != 1 {
		t.Fatal("failed to recover")
	}
}
func TestInterruptedNotificationRecoveryNeverCallsProvider(t *testing.T) {
	for _, persisted := range []bool{false, true} {
		t.Run(map[bool]string{false: "unknown", true: "persisted_result"}[persisted], func(t *testing.T) {
			db := testsupport.Database(t)
			n := notification()
			ctx := context.Background()
			_, _ = db.ReceiveNotification(ctx, n)
			id, err := db.BeginNotification(ctx, n, "+94770000000")
			if err != nil || id == "" {
				t.Fatal("claim failed")
			}
			if persisted {
				if err = db.EndOutbound(ctx, id, n.MerchantID, "synthetic.crash", "ACCEPTED", ""); err != nil {
					t.Fatal(err)
				}
			}
			_, _ = db.DB.Exec("UPDATE notification_receipts SET attempted_at=now()-interval '1 minute' WHERE id=$1", n.NotificationID)
			provider := &templateProvider{}
			processor := &processing.Notifications{Store: db, Demand: &notificationDemand{eligible: true}, Provider: provider, MerchantID: n.MerchantID}
			if err = processor.Process(ctx, n); err != nil {
				t.Fatal(err)
			}
			r, _ := db.ReceiveNotification(ctx, n)
			want := "UNKNOWN"
			if persisted {
				want = "ACCEPTED"
			}
			if r.Status != want || provider.sends.Load() != 0 {
				t.Fatal("unsafe recovery")
			}
		})
	}
}
