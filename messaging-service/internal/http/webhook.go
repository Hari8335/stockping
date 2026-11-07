package httpapi

import (
	"context"
	"crypto/subtle"
	"errors"
	"github.com/google/uuid"
	"io"
	"net/http"
	"net/url"
	"stockping.dev/messaging-service/internal/processing"
	"stockping.dev/messaging-service/internal/whatsapp"
	"time"
)

type Webhook struct {
	VerifyToken, AppSecret string
	Channels               whatsapp.ChannelResolver
	Processor              *processing.Processor
}

func (h *Webhook) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.verify(w, r)
	case http.MethodPost:
		h.receive(w, r)
	default:
		w.Header().Set("Allow", "GET, POST")
		http.Error(w, "method_not_allowed", http.StatusMethodNotAllowed)
	}
}
func (h *Webhook) verify(w http.ResponseWriter, r *http.Request) {
	q, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil || len(q["hub.mode"]) != 1 || len(q["hub.verify_token"]) != 1 || len(q["hub.challenge"]) != 1 || q.Get("hub.mode") != "subscribe" || q.Get("hub.challenge") == "" || len(q.Get("hub.challenge")) > 1024 {
		http.Error(w, "invalid_verification", 400)
		return
	}
	if h.VerifyToken == "" || subtle.ConstantTimeCompare([]byte(q.Get("hub.verify_token")), []byte(h.VerifyToken)) != 1 {
		http.Error(w, "verification_rejected", 403)
		return
	}
	w.Header().Set("Content-Type", "text/plain")
	w.WriteHeader(200)
	_, _ = io.WriteString(w, q.Get("hub.challenge"))
}
func (h *Webhook) receive(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1024*1024))
	if err != nil {
		http.Error(w, "invalid_body", 413)
		return
	}
	if len(r.Header.Values("X-Hub-Signature-256")) != 1 || !whatsapp.ValidSignature(body, r.Header.Get("X-Hub-Signature-256"), h.AppSecret) {
		http.Error(w, "signature_rejected", 401)
		return
	}
	correlation := r.Header.Get("X-Request-ID")
	parsed, err := uuid.Parse(correlation)
	if err != nil || parsed.String() != correlation {
		correlation = uuid.NewString()
	}
	w.Header().Set("X-Request-ID", correlation)
	messages, deliveries, err := whatsapp.Parse(body, h.Channels, correlation)
	if err != nil {
		code := 400
		if errors.Is(err, whatsapp.ErrChannel) {
			code = 403
		}
		http.Error(w, err.Error(), code)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	for _, d := range deliveries {
		if h.Processor.Store.Delivery(ctx, d) != nil {
			http.Error(w, "database_failure", 503)
			return
		}
	}
	for _, m := range messages {
		if err = h.Processor.Process(ctx, m); err != nil {
			http.Error(w, "database_failure", 503)
			return
		}
	}
	w.WriteHeader(200)
}
