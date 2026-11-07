package whatsapp

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"regexp"
	"stockping.dev/messaging-service/internal/model"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

var ErrMalformed = errors.New("malformed_webhook")
var ErrChannel = errors.New("unknown_channel")
var digits = regexp.MustCompile(`^[1-9][0-9]{7,14}$`)

// ChannelResolver can later be backed by a Messaging-owned mapping table.
type ChannelResolver interface {
	Resolve(waba, phoneID string) (string, bool)
}
type SingleChannel struct{ WABA, PhoneID, MerchantID string }

func (c SingleChannel) Resolve(waba, phoneID string) (string, bool) {
	return c.MerchantID, waba == c.WABA && phoneID == c.PhoneID && c.MerchantID != ""
}
func ValidSignature(body []byte, signature, secret string) bool {
	if secret == "" || !strings.HasPrefix(signature, "sha256=") {
		return false
	}
	decoded, err := hex.DecodeString(strings.TrimPrefix(signature, "sha256="))
	if err != nil || len(decoded) != sha256.Size {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write(body)
	return hmac.Equal(decoded, mac.Sum(nil))
}

type envelope struct {
	Object string `json:"object"`
	Entry  []struct {
		ID      string `json:"id"`
		Changes []struct {
			Field string `json:"field"`
			Value struct {
				Product  string `json:"messaging_product"`
				Metadata struct {
					PhoneID string `json:"phone_number_id"`
				} `json:"metadata"`
				Contacts []struct {
					ID      string `json:"wa_id"`
					Profile struct {
						Name string `json:"name"`
					} `json:"profile"`
				} `json:"contacts"`
				Messages []struct {
					ID        string `json:"id"`
					From      string `json:"from"`
					Timestamp string `json:"timestamp"`
					Type      string `json:"type"`
					Text      struct {
						Body string `json:"body"`
					} `json:"text"`
				} `json:"messages"`
				Statuses []struct {
					ID        string `json:"id"`
					Status    string `json:"status"`
					Timestamp string `json:"timestamp"`
					Errors    []struct {
						Code int `json:"code"`
					} `json:"errors"`
				} `json:"statuses"`
			} `json:"value"`
		} `json:"changes"`
	} `json:"entry"`
}

func Parse(body []byte, channels ChannelResolver, correlation string) ([]model.Message, []model.Delivery, error) {
	var payload envelope
	if !utf8.Valid(body) || json.Unmarshal(body, &payload) != nil || payload.Object != "whatsapp_business_account" || len(payload.Entry) == 0 {
		return nil, nil, ErrMalformed
	}
	messages := []model.Message{}
	deliveries := []model.Delivery{}
	for _, entry := range payload.Entry {
		for _, change := range entry.Changes {
			if change.Field != "messages" {
				continue
			}
			v := change.Value
			merchant, ok := channels.Resolve(entry.ID, v.Metadata.PhoneID)
			if !ok {
				return nil, nil, ErrChannel
			}
			if v.Product != "whatsapp" {
				return nil, nil, ErrMalformed
			}
			names := map[string]string{}
			for _, contact := range v.Contacts {
				names[contact.ID] = contact.Profile.Name
			}
			for _, m := range v.Messages {
				at, err := timestamp(m.Timestamp)
				if err != nil || m.ID == "" || len(m.ID) > 512 || !digits.MatchString(m.From) || m.Type == "" {
					return nil, nil, ErrMalformed
				}
				name := names[m.From]
				if len(name) > 200 {
					name = ""
				}
				if m.Type == "text" && (strings.TrimSpace(m.Text.Body) == "" || utf8.RuneCountInString(m.Text.Body) > 4096) {
					return nil, nil, ErrMalformed
				}
				messages = append(messages, model.Message{MerchantID: merchant, Phone: "+" + m.From, DisplayName: name, ProviderID: m.ID, Text: strings.TrimSpace(m.Text.Body), ReceivedAt: at, CorrelationID: correlation, Supported: m.Type == "text"})
			}
			for _, s := range v.Statuses {
				at, err := timestamp(s.Timestamp)
				if err != nil || s.ID == "" || len(s.ID) > 512 {
					return nil, nil, ErrMalformed
				}
				switch s.Status {
				case "sent", "delivered", "read", "failed":
				default:
					continue
				}
				code := ""
				if len(s.Errors) > 0 {
					code = strconv.Itoa(s.Errors[0].Code)
				}
				deliveries = append(deliveries, model.Delivery{MerchantID: merchant, ProviderID: s.ID, Status: s.Status, Time: at, ErrorCode: code})
			}
		}
	}
	if len(messages)+len(deliveries) > 100 {
		return nil, nil, ErrMalformed
	}
	return messages, deliveries, nil
}
func timestamp(value string) (time.Time, error) {
	n, err := strconv.ParseInt(value, 10, 64)
	if err != nil || n <= 0 || n > time.Now().Add(5*time.Minute).Unix() {
		return time.Time{}, ErrMalformed
	}
	return time.Unix(n, 0).UTC(), nil
}
