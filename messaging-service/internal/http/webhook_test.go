package httpapi

import (
	"net/http/httptest"
	"testing"
)

func TestVerification(t *testing.T) {
	for _, tc := range []struct {
		name, query string
		status      int
	}{{"correct", "hub.mode=subscribe&hub.verify_token=secret&hub.challenge=12345", 200}, {"incorrect", "hub.mode=subscribe&hub.verify_token=other&hub.challenge=12345", 403}, {"missing", "hub.mode=subscribe", 400}, {"mode", "hub.mode=other&hub.verify_token=secret&hub.challenge=1", 400}, {"bad-encoding", "hub.mode=subscribe&hub.verify_token=%zz&hub.challenge=1", 400}, {"duplicate-param", "hub.mode=subscribe&hub.mode=subscribe&hub.verify_token=secret&hub.challenge=1", 400}} {
		t.Run(tc.name, func(t *testing.T) {
			h := &Webhook{VerifyToken: "secret"}
			w := httptest.NewRecorder()
			h.ServeHTTP(w, httptest.NewRequest("GET", "/webhooks/whatsapp?"+tc.query, nil))
			if w.Code != tc.status {
				t.Fatalf("status=%d", w.Code)
			}
			if tc.status == 200 && w.Body.String() != "12345" {
				t.Fatal("challenge must be exact")
			}
		})
	}
}
func TestInvalidSignatureHasNoDependencies(t *testing.T) {
	w := httptest.NewRecorder()
	(&Webhook{AppSecret: "secret"}).ServeHTTP(w, httptest.NewRequest("POST", "/webhooks/whatsapp", nil))
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
}
