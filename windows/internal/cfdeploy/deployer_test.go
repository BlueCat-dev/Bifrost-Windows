package cfdeploy

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDeployWorkerSuccess(t *testing.T) {
	mux := http.NewServeMux()

	// 1. /accounts
	mux.HandleFunc("/accounts", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-valid-token" {
			w.WriteHeader(http.StatusUnauthorized)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"success": false,
				"errors":  []map[string]any{{"code": 10000, "message": "Authentication error"}},
			})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"result":  []map[string]any{{"id": "acc_123456", "name": "Test Account"}},
		})
	})

	// 2. /accounts/acc_123456/workers/subdomain
	mux.HandleFunc("/accounts/acc_123456/workers/subdomain", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"result":  map[string]any{"subdomain": "mysubdomain"},
		})
	})

	// 3. Worker script source
	mux.HandleFunc("/worker.js", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/javascript")
		_, _ = w.Write([]byte("// TWP Worker Script\nexport default { fetch() {} };"))
	})

	// 4. PUT /accounts/acc_123456/workers/scripts/bifrost-XXXXXX
	mux.HandleFunc("/accounts/acc_123456/workers/scripts/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/subdomain") {
			// POST enable subdomain
			if r.Method != http.MethodPost {
				http.Error(w, "bad method", http.StatusBadRequest)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"success": true,
				"result":  map[string]any{"enabled": true},
			})
			return
		}

		if r.Method != http.MethodPut {
			http.Error(w, "bad method", http.StatusBadRequest)
			return
		}

		err := r.ParseMultipartForm(10 << 20)
		if err != nil {
			t.Fatalf("failed parsing multipart: %v", err)
		}
		meta := r.FormValue("metadata")
		if !strings.Contains(meta, "worker.js") {
			t.Fatalf("missing main_module in metadata")
		}

		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"result":  map[string]any{"id": "bifrost-test"},
		})
	})

	ts := httptest.NewServer(mux)
	defer ts.Close()

	d := NewDeployer(ts.Client(), ts.URL, ts.URL+"/worker.js")

	progressReports := []int{}
	cfg, err := d.DeployWorker(context.Background(), DeployRequest{
		APIToken:  "test-valid-token",
		SecretKey: "supersecret",
	}, func(percent int, msg string) {
		progressReports = append(progressReports, percent)
	})

	if err != nil {
		t.Fatalf("expected deploy success, got error: %v", err)
	}

	if cfg == nil {
		t.Fatal("expected proxy config, got nil")
	}

	if !strings.HasSuffix(cfg.WorkerHost, ".mysubdomain.workers.dev") {
		t.Errorf("unexpected worker host: %s", cfg.WorkerHost)
	}

	if cfg.Secret != "supersecret" {
		t.Errorf("unexpected secret: %s", cfg.Secret)
	}

	if len(progressReports) == 0 || progressReports[len(progressReports)-1] != 100 {
		t.Errorf("unexpected progress reports: %v", progressReports)
	}
}

func TestDeployWorkerInvalidToken(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/accounts", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": false,
			"errors":  []map[string]any{{"code": 10000, "message": "Invalid API Token"}},
		})
	})

	ts := httptest.NewServer(mux)
	defer ts.Close()

	d := NewDeployer(ts.Client(), ts.URL, ts.URL+"/worker.js")

	_, err := d.DeployWorker(context.Background(), DeployRequest{
		APIToken: "bad-token",
	}, nil)

	if err == nil {
		t.Fatal("expected error with bad token, got nil")
	}

	if !strings.Contains(err.Error(), "Invalid API Token") {
		t.Errorf("expected error message to contain details, got: %v", err)
	}
}

func TestDeployWorkerEmptyToken(t *testing.T) {
	d := NewDeployer(nil, "", "")
	_, err := d.DeployWorker(context.Background(), DeployRequest{
		APIToken: "   ",
	}, nil)

	if err == nil {
		t.Fatal("expected error with empty token, got nil")
	}
}
