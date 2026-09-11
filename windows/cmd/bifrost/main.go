package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Qorvhex/Bifrost/windows/internal/config"
	"github.com/Qorvhex/Bifrost/windows/internal/core"
	"github.com/Qorvhex/Bifrost/windows/internal/updater"
	"github.com/Qorvhex/Bifrost/windows/internal/web"
)

var (
	Version   = "1.3.0"
	BuildTime = "2026-09-10"
)

func main() {
	var (
		httpPort   int
		socksPort  int
		configPath string
		noBrowser  bool
		showVer    bool
	)

	flag.IntVar(&httpPort, "web-port", config.DefaultHttpPort, "Local Web UI port")
	flag.IntVar(&socksPort, "socks-port", 0, "Override local SOCKS5 port")
	flag.StringVar(&configPath, "config", "", "Custom path to bifrost_config.json")
	flag.BoolVar(&noBrowser, "no-browser", false, "Do not open native GUI window or browser on launch")
	flag.BoolVar(&showVer, "version", false, "Print version and exit")
	flag.Parse()

	if showVer {
		fmt.Printf("Bifrost Windows Bridge v%s (%s)\n", Version, BuildTime)
		return
	}

	log.Printf("Starting Bifrost Windows Bridge v%s...\n", Version)

	// Clean up any leftover .old binary from a previous in-place auto-update
	updater.CleanupOldBinary()

	// 1. Initialize Configuration
	cm, err := config.NewConfigManager(configPath)
	if err != nil {
		log.Fatalf("Failed to initialize configuration: %v\n", err)
	}

	if socksPort > 0 && socksPort <= 65535 {
		_ = cm.SetLocalPort(socksPort)
	}

	cfg := cm.GetConfig()
	log.Printf("Configuration loaded. SOCKS5 Port: %d, Saved Workers: %d\n", cfg.LocalPort, len(cfg.Proxies))

	// 2. Initialize Bridge Manager
	bm := core.NewBridgeManager(cm)

	// Always start in STANDBY (stopped) mode by default on launch as requested
	log.Printf("Bridge initialized in STANDBY (stopped). Awaiting user activation.\n")

	// 3. Initialize Web UI Server
	shutdownCh := make(chan struct{})
	server := web.NewServer(httpPort, Version, cm, bm, shutdownCh)
	if err := server.Start(); err != nil {
		log.Fatalf("Failed to start Web UI server: %v\n", err)
	}

	actualPort := server.Port()
	appURL := fmt.Sprintf("http://127.0.0.1:%d/", actualPort)

	// 4. Handle System Signals (Ctrl+C, SIGTERM)
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

	// 5. Open Native Window (Primary GUI) or Fallback
	if !noBrowser {
		log.Printf("Launching native desktop window...\n")
		opened := web.RunNativeWindow(appURL, "Bifrost — MTProto WebSocket Bridge", 380, 450)
		if !opened {
			log.Printf("Native WebView2 window not available, falling back to system browser...\n")
			go func() {
				time.Sleep(300 * time.Millisecond)
				web.OpenBrowserOrApp(appURL)
			}()

			select {
			case sig := <-sigCh:
				log.Printf("Received signal %v, exiting cleanly...\n", sig)
			case <-shutdownCh:
				log.Printf("Shutdown requested via UI, exiting cleanly...\n")
			}
		} else {
			log.Printf("Native desktop window closed by user. Exiting immediately.\n")
		}
	} else {
		// Headless / Background service mode
		select {
		case sig := <-sigCh:
			log.Printf("Received signal %v, exiting cleanly...\n", sig)
		case <-shutdownCh:
			log.Printf("Shutdown requested via UI, exiting cleanly...\n")
		}
	}

	// 6. Graceful Cleanup and Hard Process Exit
	bm.Stop()
	server.Stop()
	log.Println("Bifrost terminated safely. Exiting process.")
	os.Exit(0)
}
