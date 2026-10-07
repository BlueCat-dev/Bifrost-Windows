package cfdeploy

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"strings"
	"time"

	"github.com/Qorvhex/Bifrost/windows/internal/config"
)

const (
	DefaultCloudflareAPIURL = "https://api.cloudflare.com/client/v4"
	DefaultWorkerSourceURL  = "https://raw.githubusercontent.com/Qorvhex/TWP/refs/heads/main/worker.js"
)

// Deployer handles automated Cloudflare Worker deployments via Cloudflare API
type Deployer struct {
	client          *http.Client
	apiBaseURL      string
	workerSourceURL string
}

// DeployRequest contains user credentials for deployment
type DeployRequest struct {
	APIToken  string `json:"api_token"`
	SecretKey string `json:"secret_key,omitempty"`
}

// NewDeployer creates a new Cloudflare deployer
func NewDeployer(client *http.Client, apiBaseURL, workerSourceURL string) *Deployer {
	if client == nil {
		client = &http.Client{
			Timeout: 45 * time.Second,
		}
	}
	if apiBaseURL == "" {
		apiBaseURL = DefaultCloudflareAPIURL
	}
	if workerSourceURL == "" {
		workerSourceURL = DefaultWorkerSourceURL
	}
	return &Deployer{
		client:          client,
		apiBaseURL:      strings.TrimRight(apiBaseURL, "/"),
		workerSourceURL: workerSourceURL,
	}
}

type cfResponse struct {
	Success  bool            `json:"success"`
	Errors   []cfError       `json:"errors"`
	Messages []string        `json:"messages"`
	Result   json.RawMessage `json:"result"`
}

type cfError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// DeployWorker deploys the TWP worker to Cloudflare and returns a configured ProxyConfig
func (d *Deployer) DeployWorker(ctx context.Context, req DeployRequest, onProgress func(percent int, msg string)) (*config.ProxyConfig, error) {
	cleanToken := strings.TrimSpace(req.APIToken)
	if cleanToken == "" {
		return nil, fmt.Errorf("کلید API کلادفلر نمی تواند خالی باشد")
	}

	if onProgress != nil {
		onProgress(15, "در حال بررسی اعتبار کلید و دریافت شناسه حساب کلادفلر...")
	}

	accountID, err := d.getAccountID(ctx, cleanToken)
	if err != nil {
		return nil, err
	}

	if onProgress != nil {
		onProgress(35, "در حال دریافت ساب دامین اختصاصی workers.dev...")
	}

	subdomain, err := d.getAccountSubdomain(ctx, cleanToken, accountID)
	if err != nil {
		return nil, err
	}

	if onProgress != nil {
		onProgress(50, "در حال دریافت آخرین نسخه اسکریپت ورکر...")
	}

	workerCode, err := d.fetchWorkerCode(ctx)
	if err != nil {
		return nil, err
	}

	// Generate a secure 6-digit random number
	nBig, err := rand.Int(rand.Reader, big.NewInt(900000))
	randNum := 100000
	if err == nil {
		randNum = int(nBig.Int64()) + 100000
	}
	scriptName := fmt.Sprintf("bifrost-%d", randNum)

	if onProgress != nil {
		onProgress(70, "در حال آپلود و پیکربندی اسکریپت در کلادفلر...")
	}

	cleanSecret := strings.TrimSpace(req.SecretKey)
	if err := d.uploadWorkerScript(ctx, cleanToken, accountID, scriptName, workerCode, cleanSecret); err != nil {
		return nil, err
	}

	if onProgress != nil {
		onProgress(85, "در حال فعال سازی مسیر دامنه workers.dev برای ورکر...")
	}

	if err := d.enableScriptSubdomain(ctx, cleanToken, accountID, scriptName); err != nil {
		return nil, err
	}

	if onProgress != nil {
		onProgress(100, "پروکسی با موفقیت ساخته شد و آماده اتصال است!")
	}

	workerHost := fmt.Sprintf("%s.%s.workers.dev", scriptName, subdomain)
	cfg := &config.ProxyConfig{
		ID:         fmt.Sprintf("cf_%d", time.Now().UnixNano()),
		Name:       fmt.Sprintf("CF-%d", randNum),
		WorkerHost: workerHost,
		CleanIP:    "",
		Secret:     cleanSecret,
		Port:       443,
		IsActive:   true,
		CreatedAt:  time.Now().UnixMilli(),
	}

	return cfg, nil
}

func (d *Deployer) getAccountID(ctx context.Context, token string) (string, error) {
	url := fmt.Sprintf("%s/accounts", d.apiBaseURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")

	resp, err := d.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("خطا در برقراری ارتباط با کلادفلر: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("خطا در خواندن پاسخ کلادفلر: %w", err)
	}

	var cf cfResponse
	if err := json.Unmarshal(body, &cf); err != nil {
		return "", fmt.Errorf("پاسخ نامعتبر از سرور کلادفلر")
	}

	if !cf.Success || len(cf.Errors) > 0 {
		return "", fmt.Errorf("خطای کلادفلر: %s", extractError(cf))
	}

	var accounts []struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	}
	if err := json.Unmarshal(cf.Result, &accounts); err != nil || len(accounts) == 0 {
		return "", fmt.Errorf("هیچ حساب کلادفلری مرتبط با این کلید یافت نشد")
	}

	return accounts[0].ID, nil
}

func (d *Deployer) getAccountSubdomain(ctx context.Context, token, accountID string) (string, error) {
	url := fmt.Sprintf("%s/accounts/%s/workers/subdomain", d.apiBaseURL, accountID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")

	resp, err := d.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("خطا در دریافت ساب دامین: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return "", fmt.Errorf("ساب دامین workers.dev برای این حساب تنظیم نشده است. لطفا یک بار وارد پنل کلادفلر شده و ساب دامین بخش Workers را ثبت کنید")
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("خطا در خواندن اطلاعات ساب دامین: %w", err)
	}

	var cf cfResponse
	if err := json.Unmarshal(body, &cf); err != nil {
		return "", fmt.Errorf("پاسخ ساب دامین نامعتبر است")
	}

	if !cf.Success {
		return "", fmt.Errorf("خطا در دریافت ساب دامین: %s", extractError(cf))
	}

	var sub struct {
		Subdomain string `json:"subdomain"`
	}
	if err := json.Unmarshal(cf.Result, &sub); err != nil || strings.TrimSpace(sub.Subdomain) == "" {
		return "", fmt.Errorf("ساب دامین workers.dev یافت نشد")
	}

	return strings.TrimSpace(sub.Subdomain), nil
}

func (d *Deployer) fetchWorkerCode(ctx context.Context) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, d.workerSourceURL, nil)
	if err != nil {
		return "", err
	}

	resp, err := d.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("خطا در دریافت کد ورکر از گیت هاب: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("خطا در دریافت اسکریپت ورکر با کد %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("خطا در خواندن اسکریپت ورکر: %w", err)
	}

	code := strings.TrimSpace(string(body))
	if code == "" {
		return "", fmt.Errorf("اسکریپت ورکر دریافتی خالی است")
	}

	return code, nil
}

func (d *Deployer) uploadWorkerScript(ctx context.Context, token, accountID, scriptName, workerCode, secretKey string) error {
	type binding struct {
		Type string `json:"type"`
		Name string `json:"name"`
		Text string `json:"text"`
	}

	type metadata struct {
		MainModule         string    `json:"main_module"`
		CompatibilityDate  string    `json:"compatibility_date"`
		CompatibilityFlags []string  `json:"compatibility_flags"`
		Bindings           []binding `json:"bindings,omitempty"`
	}

	meta := metadata{
		MainModule:         "worker.js",
		CompatibilityDate:  "2024-09-01",
		CompatibilityFlags: []string{"nodejs_compat"},
	}

	if secretKey != "" {
		meta.Bindings = append(meta.Bindings, binding{
			Type: "plain_text",
			Name: "SECRET",
			Text: secretKey,
		})
	}

	metaJSON, err := json.Marshal(meta)
	if err != nil {
		return err
	}

	var b bytes.Buffer
	w := multipart.NewWriter(&b)

	// metadata part
	metaHeader := make(textproto.MIMEHeader)
	metaHeader.Set("Content-Disposition", `form-data; name="metadata"`)
	metaHeader.Set("Content-Type", "application/json")
	metaPart, err := w.CreatePart(metaHeader)
	if err != nil {
		return err
	}
	if _, err := metaPart.Write(metaJSON); err != nil {
		return err
	}

	// worker.js part
	scriptHeader := make(textproto.MIMEHeader)
	scriptHeader.Set("Content-Disposition", `form-data; name="worker.js"; filename="worker.js"`)
	scriptHeader.Set("Content-Type", "application/javascript+module")
	scriptPart, err := w.CreatePart(scriptHeader)
	if err != nil {
		return err
	}
	if _, err := scriptPart.Write([]byte(workerCode)); err != nil {
		return err
	}

	if err := w.Close(); err != nil {
		return err
	}

	url := fmt.Sprintf("%s/accounts/%s/workers/scripts/%s", d.apiBaseURL, accountID, scriptName)
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, url, &b)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", w.FormDataContentType())

	resp, err := d.client.Do(req)
	if err != nil {
		return fmt.Errorf("خطا در ارسال اسکریپت ورکر به کلادفلر: %w", err)
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(resp.Body)
	var cf cfResponse
	if err := json.Unmarshal(respBody, &cf); err != nil {
		return fmt.Errorf("پاسخ سرور در زمان ایجاد ورکر نامعتبر است")
	}

	if !cf.Success {
		return fmt.Errorf("خطا در ایجاد اسکریپت ورکر: %s", extractError(cf))
	}

	return nil
}

func (d *Deployer) enableScriptSubdomain(ctx context.Context, token, accountID, scriptName string) error {
	payload := map[string]bool{"enabled": true}
	jsonBytes, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	url := fmt.Sprintf("%s/accounts/%s/workers/scripts/%s/subdomain", d.apiBaseURL, accountID, scriptName)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(jsonBytes))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	resp, err := d.client.Do(req)
	if err != nil {
		return fmt.Errorf("خطا در فعال سازی ساب دامین ورکر: %w", err)
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(resp.Body)
	var cf cfResponse
	if err := json.Unmarshal(respBody, &cf); err != nil {
		return fmt.Errorf("پاسخ سرور در زمان فعال سازی ساب دامین نامعتبر است")
	}

	if !cf.Success {
		return fmt.Errorf("خطا در فعال سازی ساب دامین ورکر: %s", extractError(cf))
	}

	return nil
}

func extractError(cf cfResponse) string {
	if len(cf.Errors) > 0 {
		var msgs []string
		for _, e := range cf.Errors {
			if e.Message != "" {
				msgs = append(msgs, e.Message)
			}
		}
		if len(msgs) > 0 {
			return strings.Join(msgs, " | ")
		}
	}
	return "خطای ناشناخته از سمت سرور کلادفلر"
}
