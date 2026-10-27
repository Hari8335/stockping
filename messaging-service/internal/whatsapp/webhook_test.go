package whatsapp

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"testing"
	"time"
)

func signed(b []byte) string {
	h := hmac.New(sha256.New, []byte("app-secret"))
	_, _ = h.Write(b)
	return "sha256=" + hex.EncodeToString(h.Sum(nil))
}
func TestSignatures(t *testing.T) {
	body := []byte(`{"message":"HELP"}`)
	for _, tc := range []struct {
		name, signature, secret string
		valid                   bool
	}{{"valid", signed(body), "app-secret", true}, {"missing", "", "app-secret", false}, {"wrong", signed(body), "other", false}, {"empty-secret", signed(body), "", false}, {"malformed", "sha256=xx", "app-secret", false}, {"wrong-algorithm", "sha1=ab", "app-secret", false}} {
		t.Run(tc.name, func(t *testing.T) {
			if ValidSignature(body, tc.signature, tc.secret) != tc.valid {
				t.Fatal("signature result")
			}
		})
	}
	if ValidSignature(append(body, ' '), signed(body), "app-secret") {
		t.Fatal("raw body not validated")
	}
}
func fixture(kind, text string) []byte {
	return []byte(fmt.Sprintf(`{"object":"whatsapp_business_account","entry":[{"id":"100","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"phone_number_id":"200"},"contacts":[{"wa_id":"94770000000","profile":{"name":"Test"}}],"messages":[{"id":"wamid.in","from":"94770000000","timestamp":"%d","type":%q,"text":{"body":%q}}]}}]}]}`, time.Now().Unix(), kind, text))
}
func TestNormalization(t *testing.T) {
	m, d, e := Parse(fixture("text", " HELP \n"), SingleChannel{"100", "200", "merchant"}, "correlation")
	if e != nil || len(m) != 1 || len(d) != 0 || m[0].Text != "HELP" || m[0].Phone != "+94770000000" || m[0].DisplayName != "Test" || m[0].MerchantID != "merchant" || !m[0].Supported {
		t.Fatalf("normalization failed: %v", e)
	}
}
func TestUnsupportedMedia(t *testing.T) {
	for _, kind := range []string{"image", "audio", "video", "document", "sticker"} {
		t.Run(kind, func(t *testing.T) {
			m, _, err := Parse(fixture(kind, ""), SingleChannel{"100", "200", "merchant"}, "c")
			if err != nil || m[0].Supported {
				t.Fatal("media must not become text")
			}
		})
	}
}
func TestMalformedWebhook(t *testing.T) {
	for _, body := range [][]byte{[]byte(`{`), []byte(`{}`), []byte(`{"object":"other","entry":[]}`), fixture("text", " ")} {
		if _, _, err := Parse(body, SingleChannel{"100", "200", "merchant"}, "c"); err == nil {
			t.Fatal("accepted malformed")
		}
	}
}
func TestUnknownChannel(t *testing.T) {
	if _, _, err := Parse(fixture("text", "HELP"), SingleChannel{"wrong", "200", "merchant"}, "c"); err != ErrChannel {
		t.Fatal("channel must be server controlled")
	}
}
