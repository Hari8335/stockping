package config

import (
	"fmt"
	"github.com/google/uuid"
	"net"
	"net/url"
	"regexp"
	"strconv"
	"time"
)

// Port validates configuration before the listener starts.
func Port(value string) (string, error) {
	if value == "" {
		return "8083", nil
	}
	port, err := strconv.Atoi(value)
	if err != nil || port < 1 || port > 65535 {
		return "", fmt.Errorf("PORT must be an integer between 1 and 65535")
	}
	return strconv.Itoa(port), nil
}

// Settings separates the enabled provider integration from credential-free health/regression startup.
type Settings struct {
	Enabled                                                                          bool
	VerifyToken, AppSecret, AccessToken, PhoneID, WABA, Version, MerchantID, BaseURL string
	DemandTarget, DemandToken, DatabaseURL                                           string
	DemandTimeout, ProviderTimeout                                                   time.Duration
	DemandTLS                                                                        bool
}

func Load(get func(string) string) (Settings, error) {
	c := Settings{VerifyToken: get("WHATSAPP_VERIFY_TOKEN"), AppSecret: get("WHATSAPP_APP_SECRET"), AccessToken: get("WHATSAPP_ACCESS_TOKEN"), PhoneID: get("WHATSAPP_PHONE_NUMBER_ID"), WABA: get("WHATSAPP_WABA_ID"), Version: get("WHATSAPP_GRAPH_API_VERSION"), MerchantID: get("WHATSAPP_MERCHANT_ID"), DemandToken: get("MESSAGING_DEMAND_TOKEN")}
	var err error
	c.Enabled, err = boolean(get("WHATSAPP_ENABLED"))
	if err != nil {
		return c, err
	}
	c.DemandTLS, err = boolean(get("DEMAND_GRPC_TLS"))
	if err != nil {
		return c, err
	}
	c.BaseURL = get("WHATSAPP_API_BASE_URL")
	if c.BaseURL == "" {
		c.BaseURL = "https://graph.facebook.com"
	}
	c.DemandTarget = get("DEMAND_GRPC_TARGET")
	if c.DemandTarget == "" {
		c.DemandTarget = "demand-service:9092"
	}
	c.DemandTimeout, err = duration(get("DEMAND_GRPC_TIMEOUT_MS"), 3000)
	if err != nil {
		return c, err
	}
	c.ProviderTimeout, err = duration(get("WHATSAPP_HTTP_TIMEOUT_MS"), 3000)
	if err != nil {
		return c, err
	}
	host := get("MESSAGING_DB_HOST")
	if host == "" {
		host = "localhost"
	}
	port := get("MESSAGING_DB_PORT")
	if port == "" {
		port = "5434"
	}
	if _, err = Port(port); err != nil {
		return c, err
	}
	user := get("MESSAGING_DB_USER")
	if user == "" {
		user = "messaging_user"
	}
	ssl := get("MESSAGING_DB_SSLMODE")
	if ssl == "" {
		ssl = "disable"
	}
	if ssl != "disable" && ssl != "verify-full" {
		return c, fmt.Errorf("MESSAGING_DB_SSLMODE must be disable or verify-full")
	}
	u := url.URL{Scheme: "postgres", User: url.UserPassword(user, get("MESSAGING_DB_PASSWORD")), Host: net.JoinHostPort(host, port), Path: "/messaging_db"}
	q := u.Query()
	q.Set("sslmode", ssl)
	q.Set("connect_timeout", "5")
	q.Set("options", "-c statement_timeout=3000 -c lock_timeout=2000")
	u.RawQuery = q.Encode()
	c.DatabaseURL = u.String()
	if !c.Enabled {
		return c, nil
	}
	if c.VerifyToken == "" || c.AppSecret == "" || c.AccessToken == "" || len(c.DemandToken) < 32 {
		return c, fmt.Errorf("enabled WhatsApp requires provider credentials and a 32-character internal service credential")
	}
	if !regexp.MustCompile(`^[0-9]+$`).MatchString(c.PhoneID) || !regexp.MustCompile(`^[0-9]+$`).MatchString(c.WABA) || !regexp.MustCompile(`^v[0-9]+\.[0-9]+$`).MatchString(c.Version) {
		return c, fmt.Errorf("invalid WhatsApp channel or Graph API version configuration")
	}
	merchant, e := uuid.Parse(c.MerchantID)
	if e != nil || merchant.String() != c.MerchantID || merchant == uuid.Nil {
		return c, fmt.Errorf("WHATSAPP_MERCHANT_ID must be a canonical UUID")
	}
	base, e := url.Parse(c.BaseURL)
	if e != nil || base.Host == "" || base.User != nil || base.RawQuery != "" || base.Fragment != "" || base.Path != "" {
		return c, fmt.Errorf("invalid provider base URL")
	}
	allowTest, e := boolean(get("WHATSAPP_ALLOW_TEST_ENDPOINT"))
	if e != nil {
		return c, e
	}
	if c.BaseURL != "https://graph.facebook.com" && (!allowTest || (base.Scheme != "http" && base.Scheme != "https")) {
		return c, fmt.Errorf("custom provider endpoint requires explicit test mode")
	}
	return c, nil
}
func boolean(s string) (bool, error) {
	if s == "" {
		return false, nil
	}
	v, e := strconv.ParseBool(s)
	if e != nil {
		return false, fmt.Errorf("invalid boolean configuration")
	}
	return v, nil
}
func duration(s string, fallback int) (time.Duration, error) {
	v := fallback
	var e error
	if s != "" {
		v, e = strconv.Atoi(s)
	}
	if e != nil || v < 50 || v > 5000 {
		return 0, fmt.Errorf("network timeout must be 50..5000ms")
	}
	return time.Duration(v) * time.Millisecond, nil
}
