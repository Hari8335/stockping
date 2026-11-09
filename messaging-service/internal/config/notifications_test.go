package config

import "testing"

func TestStockTemplateSafetyGates(t *testing.T) {
	env := map[string]string{}
	get := func(k string) string { return env[k] }
	provider := Settings{Enabled: true, BaseURL: "https://graph.facebook.com"}
	check := func(want string) {
		t.Helper()
		c, e := LoadNotifications(get, provider)
		if e != nil || c.Status != want {
			t.Fatalf("status=%s error=%v", c.Status, e)
		}
	}
	check("disabled")
	env["STOCK_NOTIFICATIONS_ENABLED"] = "true"
	check("template_configuration_required")
	env["WHATSAPP_STOCK_TEMPLATE_NAME"] = "synthetic_stock_test"
	env["WHATSAPP_STOCK_TEMPLATE_LANGUAGE"] = "en_US"
	env["WHATSAPP_STOCK_TEMPLATE_PARAMETERS"] = "product_name,sku"
	check("real_sending_disabled")
	env["WHATSAPP_STOCK_REAL_SEND_ENABLED"] = "true"
	if _, err := LoadNotifications(get, provider); err == nil {
		t.Fatal("real recipient allowlist required")
	}
	env["WHATSAPP_STOCK_ALLOWED_RECIPIENTS"] = "+94770000000"
	check("ready")
	c, _ := LoadNotifications(get, provider)
	if !c.Allows("+94770000000") || c.Allows("+94770000001") {
		t.Fatal("recipient isolation")
	}
	for _, bad := range []string{"", "product_name", "sku,sku", "arbitrary", "product_name,phone"} {
		env["WHATSAPP_STOCK_TEMPLATE_PARAMETERS"] = bad
		check("template_configuration_required")
	}
	env["WHATSAPP_STOCK_TEMPLATE_PARAMETERS"] = "sku,product_name"
	provider.BaseURL = "http://fake:8080"
	check("ready")
	provider.Enabled = false
	check("provider_disabled")
}
