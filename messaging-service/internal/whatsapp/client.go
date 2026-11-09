package whatsapp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

type SendError struct {
	Code      string
	Uncertain bool
}

func (e *SendError) Error() string { return e.Code }

type Client struct {
	endpoint, token string
	http            *http.Client
}

func NewClient(base, version, phoneID, token string, timeout time.Duration) *Client {
	return &Client{strings.TrimRight(base, "/") + "/" + version + "/" + phoneID + "/messages", token,
		&http.Client{Timeout: timeout, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}}
}
func (c *Client) Send(ctx context.Context, phone, text, correlation string) (string, error) {
	payload := struct {
		Product   string `json:"messaging_product"`
		Recipient string `json:"recipient_type"`
		To        string `json:"to"`
		Type      string `json:"type"`
		Text      struct {
			Preview bool   `json:"preview_url"`
			Body    string `json:"body"`
		} `json:"text"`
		Correlation string `json:"biz_opaque_callback_data"`
	}{Product: "whatsapp", Recipient: "individual", To: strings.TrimPrefix(phone, "+"), Type: "text", Correlation: correlation}
	payload.Text.Body = text
	body, _ := json.Marshal(payload)
	return c.send(ctx, body, correlation)
}

// SendTemplate supports configured, approved positional body text parameters only.
func (c *Client) SendTemplate(ctx context.Context, phone, name, language string, parameters []string, correlation string) (string, error) {
	values := make([]map[string]string, 0, len(parameters))
	for _, value := range parameters {
		values = append(values, map[string]string{"type": "text", "text": value})
	}
	body, err := json.Marshal(map[string]any{
		"messaging_product": "whatsapp", "recipient_type": "individual", "to": strings.TrimPrefix(phone, "+"),
		"type": "template", "biz_opaque_callback_data": correlation,
		"template": map[string]any{"name": name, "language": map[string]string{"code": language},
			"components": []any{map[string]any{"type": "body", "parameters": values}}},
	})
	if err != nil {
		return "", &SendError{"provider_configuration", false}
	}
	return c.send(ctx, body, correlation)
}

func (c *Client) send(ctx context.Context, body []byte, correlation string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(body))
	if err != nil {
		return "", &SendError{"provider_configuration", false}
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Request-ID", correlation)
	response, err := c.http.Do(req)
	if err != nil {
		code := "provider_network"
		if errors.Is(err, context.DeadlineExceeded) {
			code = "provider_timeout"
		}
		return "", &SendError{code, true}
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		code := "provider_validation"
		uncertain := false
		switch {
		case response.StatusCode == 401 || response.StatusCode == 403:
			code = "provider_authentication"
		case response.StatusCode == 429:
			code = "provider_throttled"
		case response.StatusCode >= 500:
			code = "provider_unavailable"
			uncertain = true
		case response.StatusCode >= 300 && response.StatusCode < 400:
			code = "provider_redirect"
		}
		return "", &SendError{code, uncertain}
	}
	var result struct {
		Messages []struct {
			ID string `json:"id"`
		} `json:"messages"`
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 65537))
	if err != nil || len(data) > 65536 || json.Unmarshal(data, &result) != nil || len(result.Messages) != 1 || result.Messages[0].ID == "" || len(result.Messages[0].ID) > 512 {
		return "", &SendError{"provider_invalid_response", true}
	}
	return result.Messages[0].ID, nil
}
