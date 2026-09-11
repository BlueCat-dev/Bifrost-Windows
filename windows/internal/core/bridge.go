package core

import (
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/Qorvhex/Bifrost/windows/internal/config"
)

// WebSocketBridge pipes bidirectional traffic between a Telegram TCP socket
// and a Cloudflare Worker WebSocket endpoint using the TWP protocol.
type WebSocketBridge struct {
	clientConn net.Conn
	targetIP   string
	targetPort int
	proxy      config.ProxyConfig
	wsConn     *websocket.Conn
	closed     int32
	closeOnce  sync.Once

	BytesIn  uint64
	BytesOut uint64

	onClose func(bytesIn, bytesOut uint64)
}

// NewWebSocketBridge constructs a new bridge instance
func NewWebSocketBridge(
	clientConn net.Conn,
	targetIP string,
	targetPort int,
	proxy config.ProxyConfig,
	onClose func(bytesIn, bytesOut uint64),
) *WebSocketBridge {
	return &WebSocketBridge{
		clientConn: clientConn,
		targetIP:   targetIP,
		targetPort: targetPort,
		proxy:      proxy,
		onClose:    onClose,
	}
}

// CleanHost strips schema and paths from worker host
func CleanHost(h string) string {
	h = strings.TrimSpace(h)
	h = strings.TrimPrefix(h, "https://")
	h = strings.TrimPrefix(h, "http://")
	h = strings.TrimPrefix(h, "wss://")
	h = strings.TrimPrefix(h, "ws://")
	h = strings.TrimRight(h, "/")
	if idx := strings.Index(h, "/"); idx != -1 {
		h = h[:idx]
	}
	if idx := strings.Index(h, ":"); idx != -1 {
		h = h[:idx]
	}
	return h
}

// Dial establishes the WebSocket handshake to the Cloudflare Worker.
// Returns nil on successful connection.
func (b *WebSocketBridge) Dial() error {
	workerHost := CleanHost(b.proxy.WorkerHost)
	if workerHost == "" {
		return fmt.Errorf("empty worker host")
	}

	workerPort := b.proxy.Port
	if workerPort <= 0 {
		workerPort = 443
	}

	// 1. Build WebSocket URL
	var hostWithPort string
	if workerPort != 443 {
		hostWithPort = fmt.Sprintf("%s:%d", workerHost, workerPort)
	} else {
		hostWithPort = workerHost
	}

	queryParams := url.Values{}
	queryParams.Set("ip", b.targetIP)
	queryParams.Set("port", strconv.Itoa(b.targetPort))
	if b.proxy.Secret != "" {
		queryParams.Set("secret", strings.TrimSpace(b.proxy.Secret))
	}

	wsURL := fmt.Sprintf("wss://%s/?%s", hostWithPort, queryParams.Encode())

	// 2. Configure HTTP Headers
	header := http.Header{}
	header.Set("Host", workerHost)
	header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Bifrost/1.1.0")
	if b.proxy.Secret != "" {
		header.Set("X-Worker-Secret", strings.TrimSpace(b.proxy.Secret))
	}

	// 3. Configure Dialer with Clean IP support
	cleanIP := strings.TrimSpace(b.proxy.CleanIP)
	dialer := &websocket.Dialer{
		NetDial: func(network, addr string) (net.Conn, error) {
			targetDialAddr := addr
			if cleanIP != "" {
				// Route directly to user-defined Cloudflare Clean IP / CDN IP
				targetDialAddr = net.JoinHostPort(cleanIP, strconv.Itoa(workerPort))
			}
			d := net.Dialer{Timeout: 12 * time.Second}
			return d.Dial(network, targetDialAddr)
		},
		TLSClientConfig: &tls.Config{
			ServerName:         workerHost, // Required for Cloudflare SNI
			InsecureSkipVerify: false,
		},
		HandshakeTimeout: 12 * time.Second,
		Subprotocols:     nil,
	}

	// 4. Dial WebSocket
	ws, resp, err := dialer.Dial(wsURL, header)
	if err != nil {
		statusInfo := ""
		if resp != nil {
			statusInfo = fmt.Sprintf(" (HTTP status: %d)", resp.StatusCode)
		}
		return fmt.Errorf("websocket dial failed to %s%s: %w", workerHost, statusInfo, err)
	}

	// Set large read limit so large files/media over MTProto stream smoothly
	ws.SetReadLimit(32 * 1024 * 1024) // 32 MB
	b.wsConn = ws
	return nil
}

// Run starts the bidirectional data streaming between Telegram TCP socket and Worker WebSocket.
// Blocks until one side disconnects or Close() is called.
func (b *WebSocketBridge) Run() {
	if b.wsConn == nil {
		b.Close()
		return
	}

	var wg sync.WaitGroup
	wg.Add(2)

	// Goroutine 1: Telegram TCP -> Cloudflare Worker WebSocket
	go func() {
		defer wg.Done()
		b.pipeClientToWS()
	}()

	// Goroutine 2: Cloudflare Worker WebSocket -> Telegram TCP
	go func() {
		defer wg.Done()
		b.pipeWSToClient()
	}()

	// Keepalive Ping ticker: sends a Ping frame every 25 seconds to keep Cloudflare Worker alive
	pingTicker := time.NewTicker(25 * time.Second)
	go func() {
		for {
			select {
			case <-pingTicker.C:
				if atomic.LoadInt32(&b.closed) == 1 {
					pingTicker.Stop()
					return
				}
				if b.wsConn != nil {
					_ = b.wsConn.WriteControl(websocket.PingMessage, []byte{}, time.Now().Add(5*time.Second))
				}
			}
		}
	}()

	// Wait for streaming to finish
	wg.Wait()
	pingTicker.Stop()
	b.Close()
}

func (b *WebSocketBridge) pipeClientToWS() {
	buf := make([]byte, 32768) // 32 KB read buffer
	for {
		if atomic.LoadInt32(&b.closed) == 1 {
			return
		}

		n, err := b.clientConn.Read(buf)
		if n > 0 {
			atomic.AddUint64(&b.BytesOut, uint64(n))
			if b.wsConn != nil {
				if writeErr := b.wsConn.WriteMessage(websocket.BinaryMessage, buf[:n]); writeErr != nil {
					b.Close()
					return
				}
			}
		}
		if err != nil {
			b.Close()
			return
		}
	}
}

func (b *WebSocketBridge) pipeWSToClient() {
	for {
		if atomic.LoadInt32(&b.closed) == 1 {
			return
		}

		messageType, message, err := b.wsConn.ReadMessage()
		if err != nil {
			b.Close()
			return
		}

		if messageType == websocket.BinaryMessage || messageType == websocket.TextMessage {
			atomic.AddUint64(&b.BytesIn, uint64(len(message)))
			if _, writeErr := b.clientConn.Write(message); writeErr != nil {
				b.Close()
				return
			}
		}
	}
}

// Close terminates both TCP and WebSocket connections safely
func (b *WebSocketBridge) Close() {
	b.closeOnce.Do(func() {
		atomic.StoreInt32(&b.closed, 1)

		// Close client TCP connection immediately to instantly signal Telegram
		if b.clientConn != nil {
			_ = b.clientConn.Close()
		}

		// Close WebSocket connection immediately
		if b.wsConn != nil {
			_ = b.wsConn.Close()
		}

		if b.onClose != nil {
			b.onClose(atomic.LoadUint64(&b.BytesIn), atomic.LoadUint64(&b.BytesOut))
		}
	})
}
