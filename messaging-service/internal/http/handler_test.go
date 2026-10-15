package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHTTPBoundary(t *testing.T) {
	server := httptest.NewServer(NewHandler())
	defer server.Close()
	for _, tc := range []struct {
		method, path, key, value string
		status                   int
	}{
		{"GET", "/health", "status", "UP", 200},
		{"POST", "/health", "error", "method_not_allowed", 405},
		{"GET", "/unknown", "error", "not_found", 404},
		{"GET", "/health/extra", "error", "not_found", 404},
	} {
		t.Run(tc.method+tc.path, func(t *testing.T) {
			req, _ := http.NewRequest(tc.method, server.URL+tc.path, nil)
			resp, err := server.Client().Do(req)
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			var body map[string]string
			if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if resp.StatusCode != tc.status || body[tc.key] != tc.value || resp.Header.Get("Content-Type") != "application/json" {
				t.Fatalf("unexpected response: status=%d body=%v headers=%v", resp.StatusCode, body, resp.Header)
			}
			if tc.status == 405 && resp.Header.Get("Allow") != "GET, HEAD" {
				t.Fatal("missing Allow header")
			}
		})
	}
}

func TestHeadHealthHasNoBody(t *testing.T) {
	server := httptest.NewServer(NewHandler())
	defer server.Close()
	req, _ := http.NewRequest(http.MethodHead, server.URL+"/health", nil)
	response, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || len(body) != 0 {
		t.Fatalf("unexpected HEAD response: %d %q", response.StatusCode, body)
	}
}
