package whatsapp

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestTemplateBodyAndProviderIdentifier(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Error("invalid body")
		}
		if body["type"] != "template" || body["to"] != "94770000000" || body["biz_opaque_callback_data"] != "correlation" {
			t.Error("incorrect envelope")
		}
		template := body["template"].(map[string]any)
		if template["name"] != "synthetic_stock_test" || template["language"].(map[string]any)["code"] != "en_US" {
			t.Error("incorrect configuration")
		}
		parameters := template["components"].([]any)[0].(map[string]any)["parameters"].([]any)
		if len(parameters) != 2 || parameters[0].(map[string]any)["text"] != "Product" || parameters[1].(map[string]any)["text"] != "SKU" {
			t.Error("incorrect parameters")
		}
		_, _ = w.Write([]byte(`{"messages":[{"id":"synthetic.template"}]}`))
	}))
	defer server.Close()
	id, err := NewClient(server.URL, "v26.0", "200", "test", time.Second).SendTemplate(context.Background(), "+94770000000", "synthetic_stock_test", "en_US", []string{"Product", "SKU"}, "correlation")
	if err != nil || id != "synthetic.template" {
		t.Fatal("template failed")
	}
}
