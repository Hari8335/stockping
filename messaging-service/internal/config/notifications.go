package config

import (
	"fmt"
	"net"
	"net/url"
	"regexp"
	"strings"
)

type Notifications struct {
	Enabled                               bool
	Status, BrokerURL, Template, Language string
	Parameters                            []string
	Recipients                            map[string]bool
	TestEndpoint                          bool
}

func LoadNotifications(get func(string) string, provider Settings) (Notifications, error) {
	c := Notifications{Status: "disabled", Template: get("WHATSAPP_STOCK_TEMPLATE_NAME"), Language: get("WHATSAPP_STOCK_TEMPLATE_LANGUAGE"), Recipients: map[string]bool{}}
	var err error
	c.Enabled, err = boolean(get("STOCK_NOTIFICATIONS_ENABLED"))
	if err != nil {
		return c, err
	}
	host, port := get("RABBITMQ_HOST"), get("RABBITMQ_PORT")
	if host == "" {
		host = "localhost"
	}
	if port == "" {
		port = "5673"
	}
	if _, err = Port(port); err != nil {
		return c, err
	}
	u := url.URL{Scheme: "amqp", Host: net.JoinHostPort(host, port), User: url.UserPassword(get("RABBITMQ_USER"), get("RABBITMQ_PASSWORD")), Path: "/"}
	c.BrokerURL = u.String()
	if !c.Enabled {
		return c, nil
	}
	if !provider.Enabled {
		c.Status = "provider_disabled"
		return c, nil
	}
	c.Status = "template_configuration_required"
	if !regexp.MustCompile(`^[a-z0-9_]{1,512}$`).MatchString(c.Template) || !regexp.MustCompile(`^[a-z]{2,3}(_[A-Z]{2})?$`).MatchString(c.Language) {
		return c, nil
	}
	seen := map[string]bool{}
	for _, p := range strings.Split(get("WHATSAPP_STOCK_TEMPLATE_PARAMETERS"), ",") {
		p = strings.TrimSpace(p)
		if (p != "product_name" && p != "sku" && p != "variant_description") || seen[p] {
			return c, nil
		}
		seen[p] = true
		c.Parameters = append(c.Parameters, p)
	}
	if !seen["sku"] && !seen["variant_description"] {
		return c, nil
	}
	c.TestEndpoint = provider.BaseURL != "https://graph.facebook.com" // Settings already requires the explicit test endpoint flag.
	if !c.TestEndpoint {
		real, err := boolean(get("WHATSAPP_STOCK_REAL_SEND_ENABLED"))
		if err != nil {
			return c, err
		}
		c.Status = "real_sending_disabled"
		if !real {
			return c, nil
		}
		for _, phone := range strings.Split(get("WHATSAPP_STOCK_ALLOWED_RECIPIENTS"), ",") {
			phone = strings.TrimSpace(phone)
			if !regexp.MustCompile(`^\+[1-9][0-9]{7,14}$`).MatchString(phone) {
				return c, fmt.Errorf("stock alerts require explicitly authorized recipient configuration")
			}
			c.Recipients[phone] = true
		}
	}
	c.Status = "ready"
	return c, nil
}
func (c Notifications) Allows(phone string) bool { return c.TestEndpoint || c.Recipients[phone] }
