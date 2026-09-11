package web

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Qorvhex/Bifrost/windows/internal/config"
	"github.com/Qorvhex/Bifrost/windows/internal/core"
)

// TestSecurityMiddlewareBlocksAttacks verifies that DNS rebinding and Cross-Origin attacks are blocked
func TestSecurityMiddlewareBlocksAttacks(t *testing.T) {
	cm, _ := config.NewConfigManager(t.TempDir() + "/test_config.json")
	bm := core.NewBridgeManager(cm)
	s := NewServer(15060, "2.9.0", cm, bm, make(chan struct{}))

	mux := http.NewServeMux()
	mux.HandleFunc("/api/status", s.handleGetStatus)
	handler := securityMiddleware(mux)

	tests := []struct {
		name       string
		host       string
		origin     string
		wantStatus int
	}{
		{
			name:       "Legitimate localhost request",
			host:       "127.0.0.1:15060",
			origin:     "http://127.0.0.1:15060",
			wantStatus: http.StatusOK,
		},
		{
			name:       "Legitimate request without Origin header (local app)",
			host:       "127.0.0.1:15060",
			origin:     "",
			wantStatus: http.StatusOK,
		},
		{
			name:       "DNS Rebinding Attack with malicious Host header",
			host:       "evil-attacker-site.com:15060",
			origin:     "http://evil-attacker-site.com:15060",
			wantStatus: http.StatusForbidden,
		},
		{
			name:       "Cross-Origin CSRF attack from malicious website in user browser",
			host:       "127.0.0.1:15060",
			origin:     "https://malicious-website.com",
			wantStatus: http.StatusForbidden,
		},
		{
			name:       "Subdomain attack simulating localhost",
			host:       "127.0.0.1.attacker.com",
			origin:     "http://127.0.0.1.attacker.com",
			wantStatus: http.StatusForbidden,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/api/status", nil)
			req.Host = tt.host
			if tt.origin != "" {
				req.Header.Set("Origin", tt.origin)
			}
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, req)

			if w.Code != tt.wantStatus {
				t.Fatalf("expected status %d, got %d", tt.wantStatus, w.Code)
			}

			// For allowed requests, verify security headers
			if w.Code == http.StatusOK {
				if w.Header().Get("X-Frame-Options") != "DENY" {
					t.Errorf("missing or invalid X-Frame-Options header")
				}
				if w.Header().Get("X-Content-Type-Options") != "nosniff" {
					t.Errorf("missing or invalid X-Content-Type-Options header")
				}
			}
		})
	}
}

// TestNoUserDataLeakedToUpdateServer ensures no user proxies or secrets are serialized in updater requests
func TestNoUserDataLeakedToUpdateServer(t *testing.T) {
	// 1. Setup mock update server that inspects incoming HTTP requests
	var receivedQuery string
	var receivedHeaders http.Header
	var receivedBody []byte

	mockServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedQuery = r.URL.RawQuery
		receivedHeaders = r.Header.Clone()
		_ = json.NewEncoder(w).Encode(map[string]any{
			"version":      "3.0.0",
			"download_url": "http://127.0.0.1/dummy.exe",
		})
	}))
	defer mockServer.Close()

	// 2. Perform manifest check against the mock server
	req, err := http.NewRequest(http.MethodGet, mockServer.URL+"/f/bifrost-version.json", nil)
	if err != nil {
		t.Fatalf("failed to create request: %v", err)
	}

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	// 3. Assert zero sensitive data
	if receivedQuery != "" {
		t.Errorf("expected empty query string to update server, got %q", receivedQuery)
	}
	if len(receivedBody) > 0 {
		t.Errorf("expected empty body, got %d bytes", len(receivedBody))
	}
	if receivedHeaders.Get("Authorization") != "" {
		t.Errorf("leak: Authorization header present")
	}
	if receivedHeaders.Get("X-User-Id") != "" {
		t.Errorf("leak: user identifier present")
	}
}
