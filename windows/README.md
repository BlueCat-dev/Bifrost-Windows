# Bifrost for Windows 🌈

An ultra-lightweight, native Windows desktop client for routing Telegram traffic through Cloudflare Workers via TWP (MTProto over WebSocket), featuring a zero-log local SOCKS5 bridge, Microsoft WebView2 UI, and in-app self-updating engine.

[![Platform](https://img.shields.io/badge/platform-Windows%2010%20%7C%2011-0078D6.svg)](https://github.com/Qorvhex/Bifrost)
[![Go Version](https://img.shields.io/badge/Go-1.22+-00ADD8.svg)](https://golang.org)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](../../LICENSE)
[![Latest Release](https://img.shields.io/badge/release-GitHub%20Releases-00E676.svg)](https://github.com/Qorvhex/Bifrost/releases/latest)

---

## 📥 Downloads & Releases

Precompiled and verified binaries are published directly to [GitHub Releases](https://github.com/Qorvhex/Bifrost/releases/latest):

| File | Description | Download |
|---|---|---|
| **Bifrost-Setup.exe** | Official NSIS Setup Installer (Recommended) | [Download Setup Installer](https://github.com/Qorvhex/Bifrost/releases/latest) |
| **Bifrost.exe** | Portable standalone executable (Zero install required) | [Download Portable Binary](https://github.com/Qorvhex/Bifrost/releases/latest) |
| **checksums.txt** | Cryptographic SHA-256 integrity hashes | [View Checksums](https://github.com/Qorvhex/Bifrost/releases/latest) |

---

## 🚀 Key Features

* **True Native Desktop Window:** Runs as an independent, lightweight desktop application using Microsoft WebView2 without launching external system browsers or consuming heavy framework memory.
* **Embedded Persian Typography:** Fully self-contained [Vazirmatn](https://github.com/rastikerdar/vazirmatn) font embedded directly into the PE binary.
* **Vertical Stacked Configs:** All saved worker configs are clearly visible below the action button with rapid radio switching, inline editing, and deletion.
* **In-App Glass Confirmations:** Smooth dark-glass confirmation modals for sensitive actions without locking or freezing the UI thread.
* **Bulletproof Socket Lifecycle:** Real-time raw socket tracking destroys all pending/in-flight Telegram connections upon disconnection, preventing zombie connections.
* **Cryptographically Verified In-App Auto-Updater:** Queries official GitHub Releases API, checks for updates in the background, supports Windows WinINET system proxies (Clash, v2rayN, Nekoray), and verifies SHA-256 checksums before performing in-place binary upgrades.
* **Enterprise-Grade Security:**
  * Strict loopback binding (`127.0.0.1` only).
  * Built-in Anti-CSRF and Anti-DNS Rebinding middleware.
  * Zero logging of user tokens, secrets, or message payloads.
  * Zero telemetry — no analytics, cookies, or tracking headers sent to any server.
* **Code Signing Support:** Includes NSIS installer script (`installer.nsi`) and full support for Authenticode code signing with RFC 3161 timestamps.

---

## 🛠️ Building from Source

### Prerequisites
* Go 1.22 or newer
* Windows 10/11 (64-bit) with WebView2 runtime (built-in on modern Windows), or Linux with mingw-w64 cross-compiler
* Optional: NSIS (`makensis`) for compiling the setup installer

### Build Commands
```bash
# Clone the repository
git clone https://github.com/Qorvhex/Bifrost.git
cd Bifrost/windows

# Run unit and security tests
go test -v ./...

# Compile GUI binary (No console window)
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -ldflags "-s -w -H=windowsgui -X main.Version=3.3.0" -o dist/Bifrost.exe ./cmd/bifrost

# Optional: Compile NSIS Setup Installer
makensis installer.nsi
```

---

## 🔒 Security & Privacy Architecture

1. **Local Traffic Only:** The internal web server strictly accepts requests from `127.0.0.1` and `localhost`. External origins are rejected with `403 Forbidden`.
2. **Persistent Storage:** Configurations are securely stored in `%APPDATA%\Bifrost\bifrost_config.json` and are preserved across software updates.
3. **P2P Telegram Streaming:** Telegram SOCKS5 traffic streams directly from the user's computer to their own Cloudflare Worker. No intermediate servers are involved.
4. **Verified Supply Chain:** Auto-updates strictly enforce SHA-256 verification and originate exclusively from official GitHub release assets.

---

## 📄 License
This project is licensed under the [MIT License](../../LICENSE).
Based on the TWP protocol and Android implementation by [Qorvhex](https://github.com/Qorvhex/Bifrost).
