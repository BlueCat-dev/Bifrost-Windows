package updater

import (
	"os"
	"testing"
)

func TestIsNewerVersion(t *testing.T) {
	cases := []struct {
		remote   string
		current  string
		expected bool
	}{
		{"2.2.0", "2.1.0", true},
		{"2.1.1", "2.1.0", true},
		{"3.0.0", "2.9.9", true},
		{"2.1.0", "2.1.0", false},
		{"2.0.9", "2.1.0", false},
		{"v2.3.0", "2.2.0", true},
		{"2.2.0", "v2.2.0", false},
	}

	for _, tc := range cases {
		got := isNewerVersion(tc.remote, tc.current)
		if got != tc.expected {
			t.Errorf("isNewerVersion(%q, %q) = %v; want %v", tc.remote, tc.current, got, tc.expected)
		}
	}
}

func TestParseChecksums(t *testing.T) {
	sampleChecksums := `
# Official release checksums
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  Bifrost.exe
a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e *Bifrost-Setup.exe
invalid line
`
	sums := ParseChecksums(sampleChecksums)
	if len(sums) != 2 {
		t.Fatalf("expected 2 checksum entries, got %d", len(sums))
	}

	if sums["Bifrost.exe"] != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" {
		t.Errorf("unexpected hash for Bifrost.exe: %s", sums["Bifrost.exe"])
	}
	if sums["Bifrost-Setup.exe"] != "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e" {
		t.Errorf("unexpected hash for Bifrost-Setup.exe: %s", sums["Bifrost-Setup.exe"])
	}
}

func TestExtractSHA256FromText(t *testing.T) {
	body := `
### Assets & Integrity
SHA-256 Checksums:
- Bifrost.exe: b4aca7af4d2f3b81ac8b1b02fe3ca8d6e3e17f2a55bd850f41fc1f23ac80a045
- Bifrost-Setup.exe: c5bca7af4d2f3b81ac8b1b02fe3ca8d6e3e17f2a55bd850f41fc1f23ac80a045
`
	extracted := extractSHA256FromText(body, "Bifrost.exe")
	if extracted != "b4aca7af4d2f3b81ac8b1b02fe3ca8d6e3e17f2a55bd850f41fc1f23ac80a045" {
		t.Errorf("failed to extract hash, got %s", extracted)
	}
}

func TestApplyUpdateSecurityGuards(t *testing.T) {
	// Guard 1: Non-GitHub domain rejected
	err := ApplyUpdate(&UpdateInfo{
		DownloadURL:    "https://malicious-server.com/malware.exe",
		ExpectedSHA256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
	})
	if err == nil {
		t.Errorf("expected error for non-GitHub domain, got nil")
	}

	// Guard 2: Missing SHA-256 rejected
	err = ApplyUpdate(&UpdateInfo{
		DownloadURL: "https://github.com/Qorvhex/Bifrost/releases/download/v1.0.0/Bifrost.exe",
	})
	if err == nil {
		t.Errorf("expected error for missing SHA-256 checksum, got nil")
	}
}

func TestCleanupOldBinary(t *testing.T) {
	execPath, err := os.Executable()
	if err != nil {
		t.Skip("cannot determine executable path")
	}
	oldFile := execPath + ".old"
	_ = os.WriteFile(oldFile, []byte("dummy"), 0644)
	CleanupOldBinary()
	if _, err := os.Stat(oldFile); !os.IsNotExist(err) {
		t.Errorf("expected .old binary to be cleaned up, but file still exists")
		_ = os.Remove(oldFile)
	}
}
