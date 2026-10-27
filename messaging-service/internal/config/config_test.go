package config

import "testing"

func TestPort(t *testing.T) {
	for _, tc := range []struct{ input, want string }{{"", "8083"}, {"9000", "9000"}, {"1", "1"}, {"65535", "65535"}} {
		t.Run(tc.input, func(t *testing.T) {
			got, err := Port(tc.input)
			if err != nil || got != tc.want {
				t.Fatalf("Port(%q) = %q, %v; want %q", tc.input, got, err, tc.want)
			}
		})
	}
	for _, value := range []string{"0", "-1", "65536", "abc", " 8083", "8083\n"} {
		if _, err := Port(value); err == nil {
			t.Errorf("Port(%q) accepted invalid configuration", value)
		}
	}
}

func TestProviderConfiguration(t *testing.T) {
	base := map[string]string{"WHATSAPP_ENABLED": "true", "WHATSAPP_VERIFY_TOKEN": "verify", "WHATSAPP_APP_SECRET": "app", "WHATSAPP_ACCESS_TOKEN": "access", "WHATSAPP_PHONE_NUMBER_ID": "200", "WHATSAPP_WABA_ID": "100", "WHATSAPP_GRAPH_API_VERSION": "v26.0", "WHATSAPP_MERCHANT_ID": "11111111-1111-1111-1111-111111111111", "MESSAGING_DEMAND_TOKEN": "internal-credential-more-than-32-characters"}
	if _, err := Load(func(k string) string { return base[k] }); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct{ key, value string }{{"WHATSAPP_APP_SECRET", ""}, {"MESSAGING_DEMAND_TOKEN", "short"}, {"WHATSAPP_MERCHANT_ID", "1-1-1-1-1"}, {"WHATSAPP_PHONE_NUMBER_ID", "../bad"}, {"WHATSAPP_GRAPH_API_VERSION", "latest"}, {"WHATSAPP_API_BASE_URL", "http://example.com"}, {"DEMAND_GRPC_TIMEOUT_MS", "0"}, {"WHATSAPP_HTTP_TIMEOUT_MS", "6000"}, {"WHATSAPP_ENABLED", "wrong"}} {
		t.Run(tc.key, func(t *testing.T) {
			if _, err := Load(func(k string) string {
				if k == tc.key {
					return tc.value
				}
				return base[k]
			}); err == nil {
				t.Fatal("invalid config accepted")
			}
		})
	}
	base["WHATSAPP_API_BASE_URL"] = "http://fake-provider:8080"
	base["WHATSAPP_ALLOW_TEST_ENDPOINT"] = "true"
	if _, err := Load(func(k string) string { return base[k] }); err != nil {
		t.Fatal(err)
	}
}
