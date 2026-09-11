package core

import (
	"crypto/tls"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Qorvhex/Bifrost/windows/internal/config"
)

// BridgeStatus describes the global operational state of Bifrost
type BridgeStatus string

const (
	StatusStopped   BridgeStatus = "STOPPED"
	StatusListening BridgeStatus = "LISTENING"
	StatusStreaming BridgeStatus = "STREAMING"
)

// BridgeStateSnapshot provides current state to UI / WebSocket listeners
type BridgeStateSnapshot struct {
	Status            BridgeStatus        `json:"status"`
	LocalPort         int                 `json:"local_port"`
	ActiveConnections int                 `json:"active_connections"`
	TotalBytesIn      uint64              `json:"total_bytes_in"`
	TotalBytesOut     uint64              `json:"total_bytes_out"`
	ActiveProxy       *config.ProxyConfig `json:"active_proxy"`
	Proxies           []config.ProxyConfig `json:"proxies"`
	RecentEvents      []ConnectionEvent   `json:"recent_events"`
	LastPingMs        int64               `json:"last_ping_ms"`
}

// BridgeManager coordinates the SOCKS5 server and tracks live metrics
type BridgeManager struct {
	mu           sync.RWMutex
	cm           *config.ConfigManager
	server       *Socks5Server
	status       BridgeStatus
	recentEvents []ConnectionEvent
	subscribers  []chan BridgeStateSnapshot
	totalBytesIn  uint64
	totalBytesOut uint64
	lastPingMs   int64
}

// NewBridgeManager constructs the bridge manager
func NewBridgeManager(cm *config.ConfigManager) *BridgeManager {
	bm := &BridgeManager{
		cm:           cm,
		status:       StatusStopped,
		recentEvents: make([]ConnectionEvent, 0, 50),
		subscribers:  make([]chan BridgeStateSnapshot, 0),
	}
	return bm
}

// Start launches the SOCKS5 server using the current configuration
func (bm *BridgeManager) Start() error {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	if bm.server != nil && bm.server.IsRunning() {
		return nil
	}

	cfg := bm.cm.GetConfig()
	activeProxy := bm.cm.GetActiveProxy()
	if activeProxy == nil {
		return fmt.Errorf("هیچ ورکری برای اتصال انتخاب نشده است")
	}

	bm.server = NewSocks5Server(
		cfg.LocalPort,
		func() *config.ProxyConfig {
			return bm.cm.GetActiveProxy()
		},
		func(activeCount int) {
			bm.mu.Lock()
			if bm.server == nil || !bm.server.IsRunning() {
				bm.status = StatusStopped
			} else if activeCount > 0 {
				bm.status = StatusStreaming
			} else {
				bm.status = StatusListening
			}
			bm.mu.Unlock()
			bm.broadcast()
		},
		func(event ConnectionEvent) {
			bm.mu.Lock()
			if event.BytesIn > 0 {
				bm.totalBytesIn += event.BytesIn
			}
			if event.BytesOut > 0 {
				bm.totalBytesOut += event.BytesOut
			}
			bm.recentEvents = append([]ConnectionEvent{event}, bm.recentEvents...)
			if len(bm.recentEvents) > 50 {
				bm.recentEvents = bm.recentEvents[:50]
			}
			bm.mu.Unlock()
			bm.broadcast()
		},
	)

	if err := bm.server.Start(); err != nil {
		bm.status = StatusStopped
		return err
	}

	_ = bm.cm.SetBridgeEnabled(true)
	bm.status = StatusListening

	go bm.broadcast()
	return nil
}

// Stop halts the SOCKS5 server
func (bm *BridgeManager) Stop() {
	bm.mu.Lock()
	srv := bm.server
	bm.server = nil
	bm.status = StatusStopped
	_ = bm.cm.SetBridgeEnabled(false)
	bm.mu.Unlock()

	if srv != nil {
		srv.Stop()
	}

	bm.broadcast()
}

// Restart restarts the server (e.g. when port is modified)
func (bm *BridgeManager) Restart() error {
	bm.Stop()
	return bm.Start()
}

// PingWorker measures connection latency to the worker endpoint or clean IP
func (bm *BridgeManager) PingWorker(p config.ProxyConfig) (int64, error) {
	workerHost := CleanHost(p.WorkerHost)
	if workerHost == "" {
		return 0, fmt.Errorf("empty worker host")
	}

	workerPort := p.Port
	if workerPort <= 0 {
		workerPort = 443
	}

	targetAddr := net.JoinHostPort(workerHost, strconv.Itoa(workerPort))
	if strings.TrimSpace(p.CleanIP) != "" {
		targetAddr = net.JoinHostPort(strings.TrimSpace(p.CleanIP), strconv.Itoa(workerPort))
	}

	start := time.Now()
	dialer := &net.Dialer{Timeout: 3 * time.Second}
	tlsConfig := &tls.Config{
		ServerName: workerHost,
	}

	conn, err := tls.DialWithDialer(dialer, "tcp", targetAddr, tlsConfig)
	if err != nil {
		bm.mu.Lock()
		bm.lastPingMs = -1
		bm.mu.Unlock()
		bm.broadcast()
		return 0, fmt.Errorf("connect failed to %s: %w", targetAddr, err)
	}
	_ = conn.Close()

	latency := time.Since(start).Milliseconds()
	if latency == 0 {
		latency = 1
	}

	bm.mu.Lock()
	bm.lastPingMs = latency
	bm.mu.Unlock()
	bm.broadcast()

	return latency, nil
}

// GetSnapshot returns a clone of current system state
func (bm *BridgeManager) GetSnapshot() BridgeStateSnapshot {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	activeCount := 0
	if bm.server != nil {
		activeCount = bm.server.ActiveConnections()
	}

	eventsCopy := make([]ConnectionEvent, len(bm.recentEvents))
	copy(eventsCopy, bm.recentEvents)

	return BridgeStateSnapshot{
		Status:            bm.status,
		LocalPort:         bm.cm.GetConfig().LocalPort,
		ActiveConnections: activeCount,
		TotalBytesIn:      bm.totalBytesIn,
		TotalBytesOut:     bm.totalBytesOut,
		ActiveProxy:       bm.cm.GetActiveProxy(),
		Proxies:           bm.cm.GetConfig().Proxies,
		RecentEvents:      eventsCopy,
		LastPingMs:        bm.lastPingMs,
	}
}

// Subscribe adds a channel for real-time status push notifications
func (bm *BridgeManager) Subscribe() chan BridgeStateSnapshot {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	ch := make(chan BridgeStateSnapshot, 5)
	bm.subscribers = append(bm.subscribers, ch)
	return ch
}

// Unsubscribe removes a channel
func (bm *BridgeManager) Unsubscribe(ch chan BridgeStateSnapshot) {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	for i, sub := range bm.subscribers {
		if sub == ch {
			close(sub)
			bm.subscribers = append(bm.subscribers[:i], bm.subscribers[i+1:]...)
			break
		}
	}
}

func (bm *BridgeManager) broadcast() {
	snap := bm.GetSnapshot()

	bm.mu.RLock()
	subs := make([]chan BridgeStateSnapshot, len(bm.subscribers))
	copy(subs, bm.subscribers)
	bm.mu.RUnlock()

	for _, ch := range subs {
		select {
		case ch <- snap:
		default:
		}
	}
}
