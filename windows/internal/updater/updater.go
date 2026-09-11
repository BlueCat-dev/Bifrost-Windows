package updater

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	// DefaultUpstreamRepo points to the official Bifrost upstream repository
	DefaultUpstreamRepo = "Qorvhex/Bifrost"

	// UserAgent header required by GitHub API
	UserAgentHeader = "Bifrost-Windows-Client/3.3.0 (Windows NT 10.0; Win64; x64)"
)

var (
	// Common local proxies used in restricted network environments
	knownLocalProxies = []string{
		"http://127.0.0.1:7890",   // Clash / Clash Verge / Mihomo
		"http://127.0.0.1:10809",  // v2rayN HTTP
		"socks5://127.0.0.1:10808", // v2rayN SOCKS
		"http://127.0.0.1:20811",  // NekoBox / Nekoray HTTP
		"socks5://127.0.0.1:2080",  // NekoBox SOCKS
		"socks5://127.0.0.1:5050",  // Bifrost local bridge
	}

	sha256Regex = regexp.MustCompile(`(?i)\b([a-f0-9]{64})\b`)
)

// GitHubRelease represents the schema returned by GitHub Releases API
type GitHubRelease struct {
	TagName     string        `json:"tag_name"`
	Name        string        `json:"name"`
	HTMLURL     string        `json:"html_url"`
	Body        string        `json:"body"`
	PublishedAt string        `json:"published_at"`
	Assets      []GitHubAsset `json:"assets"`
}

// GitHubAsset represents a single downloadable artifact in a GitHub Release
type GitHubAsset struct {
	Name               string `json:"name"`
	Size               int64  `json:"size"`
	BrowserDownloadURL string `json:"browser_download_url"`
	Digest             string `json:"digest"`
}

// UpdateInfo holds validated release details and cryptographic verification metadata
type UpdateInfo struct {
	Version        string `json:"version"`
	ReleaseDate    string `json:"release_date"`
	DownloadURL    string `json:"download_url"`
	SetupURL       string `json:"setup_url"`
	ReleaseURL     string `json:"release_url"`
	ExpectedSHA256 string `json:"expected_sha256"`
	Changelog      string `json:"changelog"`
	HasUpdate      bool   `json:"has_update"`
}

// GetUpdateRepo determines which GitHub repository to query for updates
func GetUpdateRepo() string {
	if repo := os.Getenv("BIFROST_UPDATE_REPO"); repo != "" {
		return strings.TrimSpace(repo)
	}
	return DefaultUpstreamRepo
}

// CheckUpdate queries official GitHub Releases API for the latest release
func CheckUpdate(currentVersion string, customRepo string) (*UpdateInfo, error) {
	repo := customRepo
	if repo == "" {
		repo = GetUpdateRepo()
	}

	apiURL := fmt.Sprintf("https://api.github.com/repos/%s/releases/latest", repo)
	rel, err := fetchGitHubRelease(apiURL)
	if err != nil {
		return nil, fmt.Errorf("failed to query official release from GitHub (%s): %w", repo, err)
	}

	remoteVersion := strings.TrimPrefix(strings.TrimSpace(rel.TagName), "v")
	if remoteVersion == "" {
		remoteVersion = strings.TrimPrefix(strings.TrimSpace(rel.Name), "v")
	}

	info := &UpdateInfo{
		Version:     remoteVersion,
		ReleaseDate: rel.PublishedAt,
		ReleaseURL:  rel.HTMLURL,
		Changelog:   rel.Body,
		HasUpdate:   isNewerVersion(remoteVersion, currentVersion),
	}

	// Locate binary assets from official release
	var checksumsAssetURL string
	for _, a := range rel.Assets {
		lowerName := strings.ToLower(a.Name)
		if lowerName == "bifrost.exe" {
			info.DownloadURL = a.BrowserDownloadURL
			if strings.HasPrefix(a.Digest, "sha256:") {
				info.ExpectedSHA256 = strings.TrimPrefix(a.Digest, "sha256:")
			}
		} else if strings.Contains(lowerName, "setup") && strings.HasSuffix(lowerName, ".exe") {
			info.SetupURL = a.BrowserDownloadURL
		} else if lowerName == "checksums.txt" || lowerName == "sha256sums.txt" {
			checksumsAssetURL = a.BrowserDownloadURL
		}
	}

	// If SHA-256 wasn't in asset metadata, fetch checksums.txt if available
	if info.ExpectedSHA256 == "" && checksumsAssetURL != "" {
		if sums, err := fetchChecksumsFile(checksumsAssetURL); err == nil {
			for fName, hash := range sums {
				if strings.EqualFold(fName, "bifrost.exe") {
					info.ExpectedSHA256 = hash
					break
				}
			}
		}
	}

	// Fallback: parse SHA-256 for Bifrost.exe from the release body notes if present
	if info.ExpectedSHA256 == "" && rel.Body != "" {
		info.ExpectedSHA256 = extractSHA256FromText(rel.Body, "Bifrost.exe")
	}

	return info, nil
}

func fetchGitHubRelease(apiURL string) (*GitHubRelease, error) {
	clients := []*http.Client{
		getSmartHTTPClient(15 * time.Second, nil),
	}

	for _, pStr := range knownLocalProxies {
		if pURL, err := url.Parse(pStr); err == nil {
			clients = append(clients, getSmartHTTPClient(8*time.Second, pURL))
		}
	}

	var lastErr error
	for _, c := range clients {
		req, err := http.NewRequest(http.MethodGet, apiURL, nil)
		if err != nil {
			lastErr = err
			continue
		}
		req.Header.Set("User-Agent", UserAgentHeader)
		req.Header.Set("Accept", "application/vnd.github.v3+json")

		resp, err := c.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		if resp.StatusCode != http.StatusOK {
			_ = resp.Body.Close()
			lastErr = fmt.Errorf("HTTP %s", resp.Status)
			continue
		}

		var rel GitHubRelease
		err = json.NewDecoder(resp.Body).Decode(&rel)
		_ = resp.Body.Close()
		if err == nil && (rel.TagName != "" || rel.Name != "") {
			return &rel, nil
		}
		lastErr = err
	}

	return nil, lastErr
}

func fetchChecksumsFile(checksumsURL string) (map[string]string, error) {
	client := getSmartHTTPClient(15*time.Second, nil)
	req, err := http.NewRequest(http.MethodGet, checksumsURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", UserAgentHeader)

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("checksum file HTTP %d", resp.StatusCode)
	}

	data, err := io.ReadAll(io.LimitReader(resp.Body, 1024*1024))
	if err != nil {
		return nil, err
	}

	return ParseChecksums(string(data)), nil
}

// ParseChecksums parses standard `sha256sum` formatted lines
func ParseChecksums(content string) map[string]string {
	result := make(map[string]string)
	scanner := bufio.NewScanner(strings.NewReader(content))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) >= 2 {
			hash := strings.ToLower(parts[0])
			filename := filepath.Base(strings.TrimPrefix(parts[1], "*"))
			if len(hash) == 64 {
				result[filename] = hash
			}
		}
	}
	return result
}

func extractSHA256FromText(text string, targetFile string) string {
	lines := strings.Split(text, "\n")
	for _, l := range lines {
		if strings.Contains(strings.ToLower(l), strings.ToLower(targetFile)) {
			if match := sha256Regex.FindString(l); match != "" {
				return strings.ToLower(match)
			}
		}
	}
	return ""
}

// isNewerVersion compares semver strings
func isNewerVersion(remote, current string) bool {
	cleanRemote := strings.TrimPrefix(strings.TrimSpace(remote), "v")
	cleanCurrent := strings.TrimPrefix(strings.TrimSpace(current), "v")

	if cleanRemote == "" || cleanCurrent == "" {
		return false
	}

	rParts := strings.Split(cleanRemote, ".")
	cParts := strings.Split(cleanCurrent, ".")

	for i := 0; i < len(rParts) && i < len(cParts); i++ {
		rNum, err1 := strconv.Atoi(rParts[i])
		cNum, err2 := strconv.Atoi(cParts[i])
		if err1 == nil && err2 == nil {
			if rNum > cNum {
				return true
			}
			if rNum < cNum {
				return false
			}
		}
	}

	return len(rParts) > len(cParts)
}

// CleanupOldBinary removes any leftover .old binary from a previous update
func CleanupOldBinary() {
	execPath, err := os.Executable()
	if err != nil {
		return
	}
	oldPath := execPath + ".old"
	if _, err := os.Stat(oldPath); err == nil {
		_ = os.Remove(oldPath)
		log.Printf("[Updater] Cleaned up temporary binary: %s\n", oldPath)
	}
}

// ApplyUpdate downloads the official executable from GitHub, strictly verifies its SHA-256 hash, and restarts
func ApplyUpdate(info *UpdateInfo) error {
	if info == nil || info.DownloadURL == "" {
		return fmt.Errorf("no valid official update information provided")
	}

	// Strict Supply Chain Guard: Ensure download URL originates from official GitHub domains
	parsedURL, err := url.Parse(info.DownloadURL)
	if err != nil {
		return fmt.Errorf("invalid download URL format: %w", err)
	}

	hostname := strings.ToLower(parsedURL.Hostname())
	if hostname != "github.com" &&
		!strings.HasSuffix(hostname, ".github.com") &&
		!strings.HasSuffix(hostname, ".githubusercontent.com") {
		return fmt.Errorf("security violation: updates can only be downloaded from official GitHub domains (got: %s)", hostname)
	}

	// Strict Integrity Guard: Require valid SHA-256 hash before applying any binary update
	cleanExpectedHash := strings.ToLower(strings.TrimSpace(info.ExpectedSHA256))
	if len(cleanExpectedHash) != 64 {
		return fmt.Errorf("security requirement: missing or invalid SHA-256 checksum for update. Refusing to install unverified binary")
	}

	execPath, err := os.Executable()
	if err != nil {
		return fmt.Errorf("unable to determine executable path: %w", err)
	}

	execPath, err = filepath.EvalSymlinks(execPath)
	if err != nil {
		return fmt.Errorf("failed to resolve executable symlinks: %w", err)
	}

	dir := filepath.Dir(execPath)
	newPath := filepath.Join(dir, "Bifrost.exe.new")
	oldPath := execPath + ".old"

	// 1. Download binary to temporary file and compute SHA-256
	computedHash, err := downloadBinaryWithHash(info.DownloadURL, newPath)
	if err != nil {
		_ = os.Remove(newPath)
		return fmt.Errorf("failed to download update binary: %w", err)
	}

	// 2. Cryptographic Checksum Validation
	if !strings.EqualFold(computedHash, cleanExpectedHash) {
		_ = os.Remove(newPath)
		return fmt.Errorf("cryptographic verification failed: checksum mismatch (expected: %s, computed: %s)", cleanExpectedHash, computedHash)
	}

	log.Printf("[Updater] SHA-256 verified successfully: %s\n", computedHash)

	// 3. Remove any previous .old file
	_ = os.Remove(oldPath)

	// 4. Rename current running executable to .old
	if err := os.Rename(execPath, oldPath); err != nil {
		_ = os.Remove(newPath)
		return fmt.Errorf("failed to stage current binary for replacement: %w", err)
	}

	// 5. Move verified .new to target path
	if err := os.Rename(newPath, execPath); err != nil {
		// Rollback
		_ = os.Rename(oldPath, execPath)
		_ = os.Remove(newPath)
		return fmt.Errorf("failed to activate new binary: %w", err)
	}

	// 6. Spawn new process detached
	cmd := exec.Command(execPath)
	cmd.Dir = dir
	if err := cmd.Start(); err != nil {
		log.Printf("[Updater] Failed to auto-restart new process: %v\n", err)
	}

	// 7. Terminate this process cleanly
	go func() {
		time.Sleep(300 * time.Millisecond)
		os.Exit(0)
	}()

	return nil
}

func downloadBinaryWithHash(downloadURL string, destPath string) (string, error) {
	clients := []*http.Client{
		getSmartHTTPClient(120*time.Second, nil),
	}

	for _, pStr := range knownLocalProxies {
		if pURL, err := url.Parse(pStr); err == nil {
			clients = append(clients, getSmartHTTPClient(120*time.Second, pURL))
		}
	}

	var lastErr error
	for _, c := range clients {
		req, err := http.NewRequest(http.MethodGet, downloadURL, nil)
		if err != nil {
			lastErr = err
			continue
		}
		req.Header.Set("User-Agent", UserAgentHeader)

		resp, err := c.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		if resp.StatusCode != http.StatusOK {
			_ = resp.Body.Close()
			lastErr = fmt.Errorf("HTTP %s", resp.Status)
			continue
		}

		outFile, err := os.OpenFile(destPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0755)
		if err != nil {
			_ = resp.Body.Close()
			return "", err
		}

		hasher := sha256.New()
		multiWriter := io.MultiWriter(outFile, hasher)

		written, err := io.Copy(multiWriter, resp.Body)
		outFile.Close()
		_ = resp.Body.Close()

		if err != nil {
			_ = os.Remove(destPath)
			lastErr = err
			continue
		}

		if written < 512*1024 {
			_ = os.Remove(destPath)
			lastErr = fmt.Errorf("incomplete binary download (%d bytes)", written)
			continue
		}

		return hex.EncodeToString(hasher.Sum(nil)), nil
	}

	return "", lastErr
}

// getSmartHTTPClient constructs an HTTP client with system proxy and local proxy support
func getSmartHTTPClient(timeout time.Duration, forceProxy *url.URL) *http.Client {
	dialer := &net.Dialer{
		Timeout:   12 * time.Second,
		KeepAlive: 30 * time.Second,
	}

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			// Prefer IPv4 to avoid broken IPv6 connectivity
			c, err := dialer.DialContext(ctx, "tcp4", addr)
			if err == nil {
				return c, nil
			}
			return dialer.DialContext(ctx, network, addr)
		},
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: false,
		},
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          10,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   12 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}

	if forceProxy != nil {
		transport.Proxy = http.ProxyURL(forceProxy)
	} else if sysProxy := getSystemProxy(); sysProxy != nil {
		transport.Proxy = http.ProxyURL(sysProxy)
	} else {
		transport.Proxy = http.ProxyFromEnvironment
	}

	return &http.Client{
		Transport: transport,
		Timeout:   timeout,
	}
}
