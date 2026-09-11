//go:build !windows
// +build !windows

package updater

import "fmt"

func runElevatedInstaller(setupPath string) error {
	return fmt.Errorf("elevated setup installer is only supported on Windows")
}
