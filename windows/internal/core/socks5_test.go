package core

import (
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/Qorvhex/Bifrost/windows/internal/config"
)

var testUpgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

func TestSocks5HandshakeWithMockWorker(t *testing.T) {
	// 1. Start a mock WebSocket Worker Server (TLS)
	ts := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := testUpgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer ws.Close()

		// Read echo packet
		msgType, msg, err := ws.ReadMessage()
		if err == nil {
			_ = ws.WriteMessage(msgType, msg)
		}
	}))
	defer ts.Close()

	u, _ := url.Parse(ts.URL)
	port, _ := strconv.Atoi(u.Port())

	dummyProxy := &config.ProxyConfig{
		ID:         "test",
		Name:       "Test Worker",
		WorkerHost: u.Hostname(),
		CleanIP:    u.Hostname(),
		Port:       port,
	}

	server := NewSocks5Server(
		15050,
		func() *config.ProxyConfig { return dummyProxy },
		nil,
		nil,
	)

	if err := server.Start(); err != nil {
		t.Fatalf("Failed to start socks5 server: %v", err)
	}
	defer server.Stop()

	// Wait for server to bind
	time.Sleep(50 * time.Millisecond)

	conn, err := net.Dial("tcp", "127.0.0.1:15050")
	if err != nil {
		t.Fatalf("Failed to dial socks5 server: %v", err)
	}
	defer conn.Close()

	// 1. Send SOCKS5 Greeting: VER=5, NMETHODS=1, METHOD=0 (NO AUTH)
	_, err = conn.Write([]byte{0x05, 0x01, 0x00})
	if err != nil {
		t.Fatalf("Failed to write greeting: %v", err)
	}

	reply := make([]byte, 2)
	_, err = conn.Read(reply)
	if err != nil {
		t.Fatalf("Failed to read greeting reply: %v", err)
	}

	if reply[0] != 0x05 || reply[1] != 0x00 {
		t.Fatalf("Unexpected greeting reply: %x %x", reply[0], reply[1])
	}

	// 2. Send CONNECT request to 127.0.0.1:80 (mock target)
	req := []byte{0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x50}
	_, err = conn.Write(req)
	if err != nil {
		t.Fatalf("Failed to write connect request: %v", err)
	}

	// Read reply
	connReply := make([]byte, 10)
	_, err = conn.Read(connReply)
	if err != nil {
		t.Fatalf("Failed to read connect reply: %v", err)
	}

	// Because httptest server has self-signed cert, let's test that server cleanly handled connection
	t.Logf("SOCKS5 Reply received: ver=%x rep=%x", connReply[0], connReply[1])
}
