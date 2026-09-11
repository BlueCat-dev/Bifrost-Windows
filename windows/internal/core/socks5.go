package core

import (
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Qorvhex/Bifrost/windows/internal/config"
)

// Socks5Server implements an RFC 1928 SOCKS5 proxy bound strictly to 127.0.0.1
type Socks5Server struct {
	port              int
	configProvider    func() *config.ProxyConfig
	listener          net.Listener
	isRunning         int32
	activeConnections int64
	mu                sync.Mutex

	// All accepted raw TCP connections
	connsMu  sync.Mutex
	allConns map[uint64]net.Conn
	connSeq  uint64

	// All active WebSocket bridges
	activeMu      sync.Mutex
	activeBridges map[uint64]*WebSocketBridge

	onConnectionEvent func(event ConnectionEvent)
	onCountChange     func(activeCount int)
}

// ConnectionEvent records real-time connection status for the UI log
type ConnectionEvent struct {
	ID        string    `json:"id"`
	Timestamp time.Time `json:"timestamp"`
	TargetIP  string    `json:"target_ip"`
	Port      int       `json:"port"`
	Status    string    `json:"status"` // "connected", "closed", "error"
	ErrorMsg  string    `json:"error_msg,omitempty"`
	BytesIn   uint64    `json:"bytes_in"`
	BytesOut  uint64    `json:"bytes_out"`
	Duration  string    `json:"duration,omitempty"`
}

// NewSocks5Server constructs a SOCKS5 server instance
func NewSocks5Server(
	port int,
	configProvider func() *config.ProxyConfig,
	onCountChange func(activeCount int),
	onConnectionEvent func(event ConnectionEvent),
) *Socks5Server {
	return &Socks5Server{
		port:              port,
		configProvider:    configProvider,
		allConns:          make(map[uint64]net.Conn),
		activeBridges:     make(map[uint64]*WebSocketBridge),
		onCountChange:     onCountChange,
		onConnectionEvent: onConnectionEvent,
	}
}

// Start binds to 127.0.0.1:<port> and begins listening for Telegram connections
func (s *Socks5Server) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if atomic.LoadInt32(&s.isRunning) == 1 {
		return nil
	}

	addr := fmt.Sprintf("127.0.0.1:%d", s.port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to bind SOCKS5 server on %s: %w", addr, err)
	}
	s.listener = listener
	atomic.StoreInt32(&s.isRunning, 1)

	go s.acceptLoop(listener)
	return nil
}

func (s *Socks5Server) acceptLoop(l net.Listener) {
	for {
		if atomic.LoadInt32(&s.isRunning) == 0 {
			return
		}

		conn, err := l.Accept()
		if err != nil {
			if atomic.LoadInt32(&s.isRunning) == 0 {
				return
			}
			continue
		}

		// Reject immediately if server stopped during accept
		if atomic.LoadInt32(&s.isRunning) == 0 {
			_ = conn.Close()
			return
		}

		s.connsMu.Lock()
		s.connSeq++
		id := s.connSeq
		if s.allConns == nil {
			s.allConns = make(map[uint64]net.Conn)
		}
		s.allConns[id] = conn
		s.connsMu.Unlock()

		// Handle connection with lifecycle tracking
		go s.handleClient(id, conn)
	}
}

func (s *Socks5Server) handleClient(id uint64, conn net.Conn) {
	connID := fmt.Sprintf("conn_%d", id)
	startTime := time.Now()

	defer func() {
		s.connsMu.Lock()
		delete(s.allConns, id)
		s.connsMu.Unlock()

		s.activeMu.Lock()
		br := s.activeBridges[id]
		delete(s.activeBridges, id)
		s.activeMu.Unlock()

		if br != nil {
			br.Close()
		}
		_ = conn.Close()
	}()

	if atomic.LoadInt32(&s.isRunning) == 0 {
		return
	}

	// Ensure TCP NoDelay for low latency MTProto streaming
	if tcpConn, ok := conn.(*net.TCPConn); ok {
		_ = tcpConn.SetNoDelay(true)
	}

	// 1. SOCKS5 Method Negotiation
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return
	}

	if atomic.LoadInt32(&s.isRunning) == 0 {
		return
	}

	ver, nMethods := header[0], int(header[1])
	if ver != 0x05 || nMethods <= 0 {
		return
	}

	methods := make([]byte, nMethods)
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}

	if atomic.LoadInt32(&s.isRunning) == 0 {
		return
	}

	// Reply: VER 0x05, METHOD 0x00 (NO AUTHENTICATION REQUIRED)
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// 2. SOCKS5 Request
	reqHeader := make([]byte, 4)
	if _, err := io.ReadFull(conn, reqHeader); err != nil {
		return
	}

	if atomic.LoadInt32(&s.isRunning) == 0 {
		return
	}

	reqVer, cmd, atyp := reqHeader[0], reqHeader[1], reqHeader[3]
	if reqVer != 0x05 || cmd != 0x01 { // 0x01 is CONNECT
		_, _ = conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var targetHost string
	switch atyp {
	case 0x01: // IPv4 (4 bytes)
		ipBuf := make([]byte, 4)
		if _, err := io.ReadFull(conn, ipBuf); err != nil {
			return
		}
		targetHost = net.IP(ipBuf).String()

	case 0x03: // Domain name
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return
		}
		domainLen := int(lenBuf[0])
		domainBuf := make([]byte, domainLen)
		if _, err := io.ReadFull(conn, domainBuf); err != nil {
			return
		}
		targetHost = string(domainBuf)

	case 0x04: // IPv6 (16 bytes)
		ipBuf := make([]byte, 16)
		if _, err := io.ReadFull(conn, ipBuf); err != nil {
			return
		}
		targetHost = net.IP(ipBuf).String()

	default:
		_, _ = conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Port (2 bytes big endian)
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBuf); err != nil {
		return
	}
	targetPort := int(binary.BigEndian.Uint16(portBuf))

	if atomic.LoadInt32(&s.isRunning) == 0 {
		return
	}

	// Get active proxy config
	activeProxy := s.configProvider()
	if activeProxy == nil {
		_, _ = conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// 3. Create Bridge instance
	bridge := NewWebSocketBridge(
		conn,
		targetHost,
		targetPort,
		*activeProxy,
		func(bytesIn, bytesOut uint64) {
			remaining := atomic.AddInt64(&s.activeConnections, -1)
			if s.onCountChange != nil {
				s.onCountChange(int(remaining))
			}
			duration := time.Since(startTime).Round(time.Millisecond).String()
			if s.onConnectionEvent != nil {
				s.onConnectionEvent(ConnectionEvent{
					ID:        connID,
					Timestamp: time.Now(),
					TargetIP:  targetHost,
					Port:      targetPort,
					Status:    "closed",
					BytesIn:   bytesIn,
					BytesOut:  bytesOut,
					Duration:  duration,
				})
			}
		},
	)

	// Register bridge in activeBridges BEFORE Dial so Stop() can abort it anytime
	s.activeMu.Lock()
	if atomic.LoadInt32(&s.isRunning) == 0 {
		s.activeMu.Unlock()
		bridge.Close()
		return
	}
	if s.activeBridges == nil {
		s.activeBridges = make(map[uint64]*WebSocketBridge)
	}
	s.activeBridges[id] = bridge
	s.activeMu.Unlock()

	// Dial WebSocket to Worker
	if err := bridge.Dial(); err != nil {
		log.Printf("[SOCKS5] Failed to connect to worker (%s) for target %s:%d: %v", activeProxy.WorkerHost, targetHost, targetPort, err)
		if s.onConnectionEvent != nil {
			s.onConnectionEvent(ConnectionEvent{
				ID:        connID,
				Timestamp: time.Now(),
				TargetIP:  targetHost,
				Port:      targetPort,
				Status:    "error",
				ErrorMsg:  err.Error(),
			})
		}
		_, _ = conn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	if atomic.LoadInt32(&s.isRunning) == 0 {
		bridge.Close()
		return
	}

	// 4. WebSocket connection is successfully OPEN!
	// Now send SOCKS5 Reply: Success (0x00)
	portHigh := byte(s.port >> 8)
	portLow := byte(s.port & 0xFF)
	if _, err := conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, portHigh, portLow}); err != nil {
		bridge.Close()
		return
	}

	// 5. Update stats & notify UI
	currentCount := atomic.AddInt64(&s.activeConnections, 1)
	if s.onCountChange != nil {
		s.onCountChange(int(currentCount))
	}

	if s.onConnectionEvent != nil {
		s.onConnectionEvent(ConnectionEvent{
			ID:        connID,
			Timestamp: startTime,
			TargetIP:  targetHost,
			Port:      targetPort,
			Status:    "connected",
		})
	}

	// 6. BLOCKING bidirectional data streaming until closed
	bridge.Run()
}

// Stop terminates the server listener, force-closes all accepted conns and active bridges
func (s *Socks5Server) Stop() {
	s.mu.Lock()
	if !atomic.CompareAndSwapInt32(&s.isRunning, 1, 0) {
		s.mu.Unlock()
		return
	}

	if s.listener != nil {
		_ = s.listener.Close()
		s.listener = nil
	}
	s.mu.Unlock()

	// 1. Force-close ALL accepted raw TCP connections
	s.connsMu.Lock()
	rawConns := make([]net.Conn, 0, len(s.allConns))
	for id, c := range s.allConns {
		rawConns = append(rawConns, c)
		delete(s.allConns, id)
	}
	s.connsMu.Unlock()

	for _, c := range rawConns {
		_ = c.Close()
	}

	// 2. Force-close ALL WebSocket bridges
	s.activeMu.Lock()
	bridges := make([]*WebSocketBridge, 0, len(s.activeBridges))
	for id, br := range s.activeBridges {
		bridges = append(bridges, br)
		delete(s.activeBridges, id)
	}
	s.activeMu.Unlock()

	for _, br := range bridges {
		br.Close()
	}

	atomic.StoreInt64(&s.activeConnections, 0)
}

// IsRunning returns true if the server is actively listening
func (s *Socks5Server) IsRunning() bool {
	return atomic.LoadInt32(&s.isRunning) == 1
}

// ActiveConnections returns the current number of active streaming connections
func (s *Socks5Server) ActiveConnections() int {
	return int(atomic.LoadInt64(&s.activeConnections))
}
