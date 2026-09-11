package web

import (
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/Qorvhex/Bifrost/windows/internal/config"
	"github.com/Qorvhex/Bifrost/windows/internal/core"
	"github.com/Qorvhex/Bifrost/windows/internal/updater"
)

//go:embed static
var staticFS embed.FS

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		origin := r.Header.Get("Origin")
		if origin == "" {
			return true // Local native app / direct client
		}
		u, err := url.Parse(origin)
		if err != nil {
			return false
		}
		h := strings.ToLower(u.Hostname())
		return h == "127.0.0.1" || h == "localhost" || h == "::1" || strings.HasPrefix(h, "127.")
	},
}

func isLoopbackHost(h string) bool {
	h = strings.TrimSpace(strings.ToLower(h))
	if h == "localhost" {
		return true
	}
	ip := net.ParseIP(h)
	return ip != nil && ip.IsLoopback()
}

// securityMiddleware protects the local Web UI server against:
// 1. DNS Rebinding attacks (validates Host header strictly to localhost/127.0.0.1)
// 2. Cross-Site Request Forgery / unauthorized cross-origin requests from external web browsers (validates Origin header)
// 3. Clickjacking / iframe embedding (X-Frame-Options: DENY)
// 4. MIME type sniffing (X-Content-Type-Options: nosniff)
func securityMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 1. Validate Host header to block DNS rebinding
		host, _, err := net.SplitHostPort(r.Host)
		if err != nil {
			host = r.Host
		}
		if !isLoopbackHost(host) {
			http.Error(w, "Forbidden: Invalid Host header", http.StatusForbidden)
			return
		}

		// 2. Validate Origin header for cross-origin attacks
		origin := r.Header.Get("Origin")
		if origin != "" {
			u, err := url.Parse(origin)
			if err != nil {
				http.Error(w, "Forbidden: Malformed Origin", http.StatusForbidden)
				return
			}
			if !isLoopbackHost(u.Hostname()) {
				http.Error(w, "Forbidden: Cross-Origin access denied", http.StatusForbidden)
				return
			}
		}

		// 3. Security Headers
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")

		next.ServeHTTP(w, r)
	})
}

// Server provides the local HTTP control dashboard and WebSocket API
type Server struct {
	port         int
	version      string
	cm           *config.ConfigManager
	bm           *core.BridgeManager
	httpServer   *http.Server
	listener     net.Listener
	shutdownCh   chan struct{}
	latestUpdate *updater.UpdateInfo
	mu           sync.Mutex
}

// Port returns the actual bound port
func (s *Server) Port() int {
	return s.port
}

// NewServer constructs the Web UI server
func NewServer(port int, version string, cm *config.ConfigManager, bm *core.BridgeManager, shutdownCh chan struct{}) *Server {
	return &Server{
		port:       port,
		version:    version,
		cm:         cm,
		bm:         bm,
		shutdownCh: shutdownCh,
	}
}

// Start runs the HTTP server on 127.0.0.1:<port>
func (s *Server) Start() error {
	mux := http.NewServeMux()

	// REST APIs
	mux.HandleFunc("/api/status", s.handleGetStatus)
	mux.HandleFunc("/api/bridge/toggle", s.handleToggleBridge)
	mux.HandleFunc("/api/proxies", s.handleProxies)
	mux.HandleFunc("/api/proxies/import", s.handleImportProxy)
	mux.HandleFunc("/api/proxies/active", s.handleSetActiveProxy)
	mux.HandleFunc("/api/settings", s.handleSettings)
	mux.HandleFunc("/api/settings/port", s.handleSettings)
	mux.HandleFunc("/api/ping", s.handlePing)
	mux.HandleFunc("/api/update/check", s.handleCheckUpdate)
	mux.HandleFunc("/api/update/apply", s.handleApplyUpdate)
	mux.HandleFunc("/api/launch-telegram", s.handleLaunchTelegram)
	mux.HandleFunc("/api/shutdown", s.handleShutdown)

	// WebSocket Live Stream
	mux.HandleFunc("/ws/live", s.handleWebSocketLive)

	// Embedded Static UI
	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		return fmt.Errorf("failed to locate embedded static assets: %w", err)
	}
	mux.Handle("/", http.FileServer(http.FS(sub)))

	// Synchronously bind to local TCP port (with retries if in TIME_WAIT)
	var listener net.Listener
	var bindErr error

	for attempt := 0; attempt < 8; attempt++ {
		addr := fmt.Sprintf("127.0.0.1:%d", s.port)
		listener, bindErr = net.Listen("tcp", addr)
		if bindErr == nil {
			break
		}
		time.Sleep(150 * time.Millisecond)
	}

	// If primary port is still occupied, try incremental fallback ports 5056..5065
	if bindErr != nil {
		for p := s.port + 1; p <= s.port + 10; p++ {
			addr := fmt.Sprintf("127.0.0.1:%d", p)
			listener, bindErr = net.Listen("tcp", addr)
			if bindErr == nil {
				s.port = p
				break
			}
		}
	}

	if bindErr != nil {
		return fmt.Errorf("failed to bind Web UI server on any local port: %w", bindErr)
	}

	s.listener = listener
	s.httpServer = &http.Server{
		Handler:      securityMiddleware(mux),
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	go func() {
		if err := s.httpServer.Serve(listener); err != nil && err != http.ErrServerClosed {
			log.Printf("[Web] Server error: %v\n", err)
		}
	}()

	return nil
}

// Stop terminates the HTTP server
func (s *Server) Stop() {
	if s.listener != nil {
		_ = s.listener.Close()
	}
	if s.httpServer != nil {
		_ = s.httpServer.Close()
	}
}

func (s *Server) writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func (s *Server) handleGetStatus(w http.ResponseWriter, r *http.Request) {
	snap := s.bm.GetSnapshot()
	s.writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"data":    snap,
		"version": s.version,
		"config":  s.cm.GetConfig(),
	})
}

func (s *Server) handleToggleBridge(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	snap := s.bm.GetSnapshot()
	if snap.Status == core.StatusStopped {
		if err := s.bm.Start(); err != nil {
			s.writeJSON(w, http.StatusOK, map[string]any{"success": false, "error": err.Error(), "data": s.bm.GetSnapshot()})
			return
		}
	} else {
		s.bm.Stop()
	}

	s.writeJSON(w, http.StatusOK, map[string]any{"success": true, "data": s.bm.GetSnapshot()})
}

func (s *Server) handleProxies(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.writeJSON(w, http.StatusOK, map[string]any{"success": true, "data": s.cm.GetConfig().Proxies})

	case http.MethodPost:
		var p config.ProxyConfig
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			s.writeJSON(w, http.StatusBadRequest, map[string]any{"success": false, "error": "invalid json payload"})
			return
		}
		if p.WorkerHost == "" {
			s.writeJSON(w, http.StatusBadRequest, map[string]any{"success": false, "error": "worker_host is required"})
			return
		}
		if p.Name == "" {
			p.Name = p.WorkerHost
		}
		if err := s.cm.AddOrUpdateProxy(p, true); err != nil {
			s.writeJSON(w, http.StatusInternalServerError, map[string]any{"success": false, "error": err.Error()})
			return
		}
		active := s.cm.GetActiveProxy()
		s.writeJSON(w, http.StatusOK, map[string]any{
			"success": true,
			"proxy":   active,
			"data":    s.cm.GetConfig().Proxies,
		})

	case http.MethodDelete:
		var req struct {
			ID string `json:"id"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
			s.writeJSON(w, http.StatusBadRequest, map[string]any{"success": false, "error": "proxy ID required"})
			return
		}
		if err := s.cm.DeleteProxy(req.ID); err != nil {
			s.writeJSON(w, http.StatusInternalServerError, map[string]any{"success": false, "error": err.Error()})
			return
		}
		s.writeJSON(w, http.StatusOK, map[string]any{"success": true, "data": s.cm.GetConfig().Proxies})

	default:
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) handleImportProxy(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		Link string `json:"link"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Link == "" {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"success": false, "error": "link required"})
		return
	}

	parsed, err := config.ParseTwpLink(req.Link)
	if err != nil {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"success": false, "error": fmt.Sprintf("invalid link: %v", err)})
		return
	}

	if err := s.cm.AddOrUpdateProxy(*parsed, true); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{"success": false, "error": err.Error()})
		return
	}

	s.writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"proxy":   parsed,
		"data":    s.cm.GetConfig().Proxies,
	})
}

func (s *Server) handleSetActiveProxy(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"success": false, "error": "proxy ID required"})
		return
	}

	if err := s.cm.SetActiveProxy(req.ID); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{"success": false, "error": err.Error()})
		return
	}

	if s.bm.GetSnapshot().Status != core.StatusStopped {
		_ = s.bm.Restart()
	}

	s.writeJSON(w, http.StatusOK, map[string]any{
		"success":   true,
		"active_id": req.ID,
		"active":    s.cm.GetActiveProxy(),
		"data":      s.bm.GetSnapshot(),
	})
}

func (s *Server) handleSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		Port      int `json:"port"`
		LocalPort int `json:"local_port"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"success": false, "error": "invalid payload"})
		return
	}

	targetPort := req.LocalPort
	if targetPort == 0 {
		targetPort = req.Port
	}
	if targetPort < 1024 || targetPort > 65535 {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"success": false, "error": "شماره پورت باید بین ۱۰۲۴ تا ۶۵۵۳۵ باشد"})
		return
	}

	oldPort := s.cm.GetConfig().LocalPort
	if err := s.cm.SetLocalPort(targetPort); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{"success": false, "error": err.Error()})
		return
	}

	if oldPort != targetPort && s.bm.GetSnapshot().Status != core.StatusStopped {
		_ = s.bm.Restart()
	}

	s.writeJSON(w, http.StatusOK, map[string]any{
		"success":    true,
		"local_port": targetPort,
		"port":       targetPort,
		"data":       s.bm.GetSnapshot(),
	})
}

func (s *Server) handlePing(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	active := s.cm.GetActiveProxy()
	if active == nil {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{"success": false, "error": "no active proxy configured"})
		return
	}

	latency, err := s.bm.PingWorker(*active)
	if err != nil {
		s.writeJSON(w, http.StatusOK, map[string]any{"success": false, "error": err.Error()})
		return
	}

	s.writeJSON(w, http.StatusOK, map[string]any{
		"success":    true,
		"latency_ms": latency,
	})
}

func (s *Server) handleCheckUpdate(w http.ResponseWriter, r *http.Request) {
	info, err := updater.CheckUpdate(s.version, "")
	if err != nil {
		s.writeJSON(w, http.StatusOK, map[string]any{
			"success":         false,
			"error":           err.Error(),
			"current_version": s.version,
		})
		return
	}

	s.mu.Lock()
	s.latestUpdate = info
	s.mu.Unlock()

	s.writeJSON(w, http.StatusOK, map[string]any{
		"success":         true,
		"has_update":      info.HasUpdate,
		"current_version": s.version,
		"update":          info,
	})
}

func (s *Server) handleApplyUpdate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	s.mu.Lock()
	info := s.latestUpdate
	s.mu.Unlock()

	if info == nil || !info.HasUpdate {
		var err error
		info, err = updater.CheckUpdate(s.version, "")
		if err != nil || info == nil || !info.HasUpdate {
			s.writeJSON(w, http.StatusBadRequest, map[string]any{
				"success": false,
				"error":   "نسخه جدیدی برای به‌روزرسانی یافت نشد",
			})
			return
		}
		s.mu.Lock()
		s.latestUpdate = info
		s.mu.Unlock()
	}

	if info.DownloadURL == "" || info.ExpectedSHA256 == "" {
		s.writeJSON(w, http.StatusBadRequest, map[string]any{
			"success": false,
			"error":   "هش امنیتی SHA-256 یا فایل رسمی در این نسخه موجود نیست. جهت امنیت شما، از نصب باینری تأییدنشده جلوگیری شد.",
		})
		return
	}

	if err := updater.ApplyUpdate(info); err != nil {
		s.writeJSON(w, http.StatusInternalServerError, map[string]any{
			"success": false,
			"error":   err.Error(),
		})
		return
	}

	s.writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"message": "به‌روزرسانی رسمی گیت‌هاب با موفقیت راستی‌آزمایی، دریافت و اعمال شد. برنامه در حال راه‌اندازی مجدد است.",
	})
}

func (s *Server) handleLaunchTelegram(w http.ResponseWriter, r *http.Request) {
	port := s.cm.GetConfig().LocalPort
	tgURI := fmt.Sprintf("tg://socks?server=127.0.0.1&port=%d", port)
	httpsURI := fmt.Sprintf("https://t.me/socks?server=127.0.0.1&port=%d", port)

	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		// Windows: start tg://socks?...
		cmd = exec.Command("cmd", "/c", "start", "", tgURI)
	case "darwin":
		cmd = exec.Command("open", tgURI)
	default:
		cmd = exec.Command("xdg-open", tgURI)
	}

	if cmd != nil {
		_ = cmd.Start()
	}

	s.writeJSON(w, http.StatusOK, map[string]any{
		"success":   true,
		"tg_uri":    tgURI,
		"https_uri": httpsURI,
	})
}

func (s *Server) handleShutdown(w http.ResponseWriter, r *http.Request) {
	s.writeJSON(w, http.StatusOK, map[string]any{"success": true, "message": "shutting down"})
	go func() {
		time.Sleep(100 * time.Millisecond)
		s.bm.Stop()
		s.Stop()
		select {
		case <-s.shutdownCh:
		default:
			close(s.shutdownCh)
		}
		os.Exit(0)
	}()
}

func (s *Server) handleWebSocketLive(w http.ResponseWriter, r *http.Request) {
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer ws.Close()

	sub := s.bm.Subscribe()
	defer s.bm.Unsubscribe(sub)

	// Send initial snapshot immediately
	initialSnap := s.bm.GetSnapshot()
	if err := ws.WriteJSON(initialSnap); err != nil {
		return
	}

	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case snap, ok := <-sub:
			if !ok {
				return
			}
			if err := ws.WriteJSON(snap); err != nil {
				return
			}
		case <-ticker.C:
			// Heartbeat
			if err := ws.WriteControl(websocket.PingMessage, []byte{}, time.Now().Add(time.Second)); err != nil {
				return
			}
		}
	}
}

// OpenBrowserOrApp launches Edge in application mode or opens default browser
func OpenBrowserOrApp(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		// Try Microsoft Edge in application mode: msedge --app=http://127.0.0.1:5055/ --window-size=480,800
		edgePath, err := exec.LookPath("msedge")
		if err == nil {
			cmd = exec.Command(edgePath, fmt.Sprintf("--app=%s", url), "--window-size=500,820")
		} else {
			// Fallback to start
			cmd = exec.Command("cmd", "/c", "start", "", url)
		}
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}

	if cmd != nil {
		_ = cmd.Start()
	}
}
