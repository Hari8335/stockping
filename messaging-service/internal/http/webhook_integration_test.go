//go:build integration

package httpapi

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"github.com/google/uuid"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	demandv1 "stockping.dev/messaging-service/gen/stockping/demand/v1"
	demandgrpc "stockping.dev/messaging-service/internal/grpc"
	"stockping.dev/messaging-service/internal/processing"
	"stockping.dev/messaging-service/internal/testsupport"
	"stockping.dev/messaging-service/internal/whatsapp"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type testDemand struct {
	demandv1.UnimplementedDemandServiceServer
	calls atomic.Int32
	mode  string
}

func (s *testDemand) ProcessCustomerMessage(ctx context.Context, r *demandv1.ProcessCustomerMessageRequest) (*demandv1.ProcessCustomerMessageResponse, error) {
	s.calls.Add(1)
	md, _ := metadata.FromIncomingContext(ctx)
	if len(md.Get("x-service-token")) != 1 || md.Get("x-service-token")[0] != "test-internal-credential-32-characters" {
		return nil, status.Error(codes.Unauthenticated, "denied")
	}
	if _, ok := ctx.Deadline(); !ok {
		return nil, status.Error(codes.Internal, "deadline missing")
	}
	if s.mode == "timeout" {
		<-ctx.Done()
		return nil, status.Error(codes.DeadlineExceeded, "deadline")
	}
	if s.mode == "unavailable" {
		return nil, status.Error(codes.Unavailable, "offline")
	}
	action := demandv1.ReplyAction_SEND_TEXT
	if s.mode == "no-reply" {
		action = demandv1.ReplyAction_NO_REPLY
	}
	return &demandv1.ProcessCustomerMessageResponse{Action: action, ReplyText: "Reply", Purpose: "HELP", CorrelationId: r.CorrelationId}, nil
}
func TestSignedWebhookPostgresGRPCProvider(t *testing.T) {
	for _, mode := range []string{"success", "timeout", "unavailable", "no-reply", "provider-failure", "unsupported", "expired"} {
		t.Run(mode, func(t *testing.T) {
			db := testsupport.Database(t)
			svc := &testDemand{mode: mode}
			listener, e := net.Listen("tcp", "127.0.0.1:0")
			if e != nil {
				t.Fatal(e)
			}
			server := grpc.NewServer()
			demandv1.RegisterDemandServiceServer(server, svc)
			go server.Serve(listener)
			defer server.Stop()
			client, e := demandgrpc.New(listener.Addr().String(), "test-internal-credential-32-characters", 100*time.Millisecond, false)
			if e != nil {
				t.Fatal(e)
			}
			defer client.Close()
			var sends atomic.Int32
			provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				sends.Add(1)
				if mode == "provider-failure" {
					w.WriteHeader(500)
					return
				}
				_, _ = w.Write([]byte(`{"messages":[{"id":"wamid.reply"}]}`))
			}))
			defer provider.Close()
			merchant := uuid.NewString()
			handler := &Webhook{VerifyToken: "verify", AppSecret: "secret", Channels: whatsapp.SingleChannel{WABA: "100", PhoneID: "200", MerchantID: merchant}, Processor: &processing.Processor{Store: db, Demand: client, Provider: whatsapp.NewClient(provider.URL, "v26.0", "200", "test", time.Second), Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}}
			kind := "text"
			if mode == "unsupported" {
				kind = "image"
			}
			at := time.Now()
			if mode == "expired" {
				at = at.Add(-25 * time.Hour)
			}
			body := []byte(fmt.Sprintf(`{"object":"whatsapp_business_account","entry":[{"id":"100","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"phone_number_id":"200"},"messages":[{"id":"wamid.in","from":"94770000000","timestamp":"%d","type":%q,"text":{"body":"HELP"}}]}}]}]}`, at.Unix(), kind))
			mac := hmac.New(sha256.New, []byte("secret"))
			_, _ = mac.Write(body)
			signature := "sha256=" + hex.EncodeToString(mac.Sum(nil))
			post := func(sig string) int {
				r := httptest.NewRequest("POST", "/webhooks/whatsapp", bytes.NewReader(body))
				r.Header.Set("X-Hub-Signature-256", sig)
				w := httptest.NewRecorder()
				handler.ServeHTTP(w, r)
				return w.Code
			}
			if post("invalid") != 401 {
				t.Fatal("signature")
			}
			var count int
			_ = db.DB.QueryRow("SELECT count(*) FROM webhook_events").Scan(&count)
			if count != 0 || svc.calls.Load() != 0 {
				t.Fatal("unauthenticated side effects")
			}
			var wg sync.WaitGroup
			for i := 0; i < 10; i++ {
				wg.Go(func() {
					if code := post(signature); code != 200 {
						t.Errorf("status=%d", code)
					}
				})
			}
			wg.Wait()
			wantCalls := int32(1)
			wantSends := int32(1)
			state := "PROCESSED"
			switch mode {
			case "timeout", "unavailable":
				state = "DEMAND_FAILED"
				wantSends = 0
			case "no-reply":
				wantSends = 0
			case "provider-failure":
				state = "PROVIDER_FAILED"
			case "unsupported":
				state = "UNSUPPORTED"
				wantCalls = 0
				wantSends = 0
			case "expired":
				state = "EXPIRED"
				wantSends = 0
			}
			var got string
			_ = db.DB.QueryRow("SELECT status FROM webhook_events WHERE event_type='INBOUND'").Scan(&got)
			if svc.calls.Load() != wantCalls || sends.Load() != wantSends || got != state {
				t.Fatalf("calls=%d sends=%d state=%s", svc.calls.Load(), sends.Load(), got)
			}
			_ = db.DB.QueryRow("SELECT count(*) FROM message_records WHERE direction='INBOUND'").Scan(&count)
			if count != 1 {
				t.Fatal("duplicate inbound")
			}
			if mode == "provider-failure" {
				var missing bool
				_ = db.DB.QueryRow("SELECT provider_message_id IS NULL FROM message_records WHERE direction='OUTBOUND'").Scan(&missing)
				if !missing {
					t.Fatal("fabricated provider ID")
				}
			}
		})
	}
}
