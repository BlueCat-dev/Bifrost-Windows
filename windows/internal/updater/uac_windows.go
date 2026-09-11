//go:build windows
// +build windows

package updater

import (
	"fmt"
	"os/exec"
)

// runElevatedInstaller launches an installer with standard Windows UAC elevation
func runElevatedInstaller(setupPath string) error {
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
		fmt.Sprintf("Start-Process -FilePath '%s' -Verb RunAs -ArgumentList '/S'", setupPath))
	return cmd.Start()
}
