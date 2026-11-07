//go:build integration

package store_test

import (
	"context"
	"github.com/google/uuid"
	"stockping.dev/messaging-service/internal/model"
	"stockping.dev/messaging-service/internal/testsupport"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func message() model.Message {
	return model.Message{MerchantID: uuid.NewString(), Phone: "+94770000000", ProviderID: "wamid.in", Text: "HELP", CorrelationID: uuid.NewString(), ReceivedAt: time.Now(), Supported: true}
}
func TestMigrationAndConcurrentDeduplication(t *testing.T) {
	p := testsupport.Database(t)
	ctx := context.Background()
	if err := p.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	m := message()
	var calls atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Go(func() {
			id, e := p.Claim(ctx, m)
			if e != nil {
				t.Error(e)
			}
			if id != "" {
				calls.Add(1)
			}
		})
	}
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatal("duplicate claimed")
	}
	for _, table := range []string{"webhook_events", "message_records", "schema_migrations"} {
		var n int
		if p.DB.QueryRow("SELECT count(*) FROM "+table).Scan(&n) != nil || n != 1 {
			t.Fatalf("wrong %s rows", table)
		}
	}
	m.MerchantID = uuid.NewString()
	if id, e := p.Claim(ctx, m); e != nil || id == "" {
		t.Fatal("tenant-scoped identifier")
	}
}
func TestClaimRollbackAndMigrationChecksum(t *testing.T) {
	p := testsupport.Database(t)
	ctx := context.Background()
	m := message()
	m.Phone = "invalid"
	if _, err := p.Claim(ctx, m); err == nil {
		t.Fatal("phone constraint")
	}
	var n int
	_ = p.DB.QueryRow("SELECT count(*) FROM webhook_events").Scan(&n)
	if n != 0 {
		t.Fatal("claim not atomic")
	}
	_, _ = p.DB.Exec("UPDATE schema_migrations SET checksum='changed'")
	if p.Migrate(ctx) == nil {
		t.Fatal("migration drift accepted")
	}
}
func TestOutboundAndChronologicalDelivery(t *testing.T) {
	p := testsupport.Database(t)
	ctx := context.Background()
	m := message()
	event, e := p.Claim(ctx, m)
	if e != nil {
		t.Fatal(e)
	}
	out, e := p.BeginOutbound(ctx, event, m, "HELP")
	if e != nil {
		t.Fatal(e)
	}
	now := time.Now().Truncate(time.Second)
	d := model.Delivery{MerchantID: m.MerchantID, ProviderID: "wamid.out", Status: "read", Time: now}
	// Callback before provider result; duplicate and out-of-order callbacks remain safe.
	if e = p.Delivery(ctx, d); e != nil {
		t.Fatal(e)
	}
	if e = p.Delivery(ctx, d); e != nil {
		t.Fatal(e)
	}
	if e = p.EndOutbound(ctx, out, m.MerchantID, "wamid.out", "ACCEPTED", ""); e != nil {
		t.Fatal(e)
	}
	for i, status := range []string{"delivered", "sent", "failed"} {
		d.Status = status
		d.Time = now.Add(-time.Duration(i+1) * time.Second)
		if e = p.Delivery(ctx, d); e != nil {
			t.Fatal(e)
		}
	}
	var state string
	var count, linked int
	_ = p.DB.QueryRow("SELECT status FROM message_records WHERE id=$1", out).Scan(&state)
	_ = p.DB.QueryRow("SELECT count(*),count(message_record_id) FROM delivery_events").Scan(&count, &linked)
	if state != "READ" || count != 4 || linked != 4 {
		t.Fatalf("state=%s count=%d linked=%d", state, count, linked)
	}
	d.ProviderID = "unknown"
	if e = p.Delivery(ctx, d); e != nil {
		t.Fatal(e)
	}
	_ = p.DB.QueryRow("SELECT count(*) FROM delivery_events WHERE message_record_id IS NULL").Scan(&count)
	if count != 1 {
		t.Fatal("unknown callback lost")
	}
	if e = p.Finish(ctx, event, "PROCESSED", ""); e != nil {
		t.Fatal(e)
	}
	var at *time.Time
	_ = p.DB.QueryRow("SELECT processed_at FROM webhook_events WHERE id=$1", event).Scan(&at)
	if at == nil {
		t.Fatal("no processed timestamp")
	}
}
