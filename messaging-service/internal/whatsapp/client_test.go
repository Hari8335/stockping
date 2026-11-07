package whatsapp

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestTextRequest(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v26.0/200/messages" || r.Method != "POST" || r.Header.Get("Authorization") != "Bearer test-token" || r.Header.Get("Content-Type") != "application/json" {
			t.Error("wrong endpoint/headers")
		}
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Error("bad JSON")
		}
		text, _ := body["text"].(map[string]any)
		if body["to"] != "94770000000" || body["messaging_product"] != "whatsapp" || body["type"] != "text" || text["body"] != "Reply" || body["biz_opaque_callback_data"] != "correlation" || r.Header.Get("X-Request-ID") != "correlation" {
			t.Error("wrong payload")
		}
		_, _ = w.Write([]byte(`{"messages":[{"id":"wamid.out"}]}`))
	}))
	defer s.Close()
	id, err := NewClient(s.URL, "v26.0", "200", "test-token", time.Second).Send(context.Background(), "+94770000000", "Reply", "correlation")
	if err != nil || id != "wamid.out" {
		t.Fatalf("send: %v", err)
	}
}
func TestProviderFailures(t *testing.T) {
	for _, tc := range []struct {
		name       string
		status     int
		body, code string
		unknown    bool
	}{{"bad-request", 400, "private details", "provider_validation", false}, {"auth", 401, "token", "provider_authentication", false}, {"forbidden", 403, "", "provider_authentication", false}, {"throttle", 429, "", "provider_throttled", false}, {"server", 500, "", "provider_unavailable", true}, {"malformed", 200, "{", "provider_invalid_response", true}, {"missing-id", 200, `{"messages":[]}`, "provider_invalid_response", true}, {"redirect", 302, "", "provider_redirect", false}} {
		t.Run(tc.name, func(t *testing.T) {
			count := 0
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				count++
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer s.Close()
			id, err := NewClient(s.URL, "v99.0", "200", "token", time.Second).Send(context.Background(), "+94770000000", "Reply", "c")
			var failure *SendError
			if id != "" || !errors.As(err, &failure) || failure.Code != tc.code || failure.Uncertain != tc.unknown || count != 1 {
				t.Fatalf("failure result id=%q err=%v calls=%d", id, err, count)
			}
		})
	}
}
func TestProviderTimeout(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { time.Sleep(100 * time.Millisecond) }))
	defer s.Close()
	_, err := NewClient(s.URL, "v26.0", "200", "token", 20*time.Millisecond).Send(context.Background(), "+94770000000", "Reply", "c")
	var failure *SendError
	if !errors.As(err, &failure) || failure.Code != "provider_timeout" || !failure.Uncertain {
		t.Fatalf("timeout %v", err)
	}
}
