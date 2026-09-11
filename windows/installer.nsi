!include "MUI2.nsh"
!include "FileFunc.nsh"

; General Configuration
Name "Bifrost for Windows"
OutFile "dist/Bifrost-Setup.exe"
InstallDir "$PROGRAMFILES64\Bifrost"
InstallDirRegKey HKLM "Software\Bifrost" "InstallDir"
RequestExecutionLevel admin
SetCompressor /SOLID lzma

; Branding & Metadata
VIProductVersion "3.3.0.0"
VIAddVersionKey "ProductName" "Bifrost for Windows"
VIAddVersionKey "CompanyName" "Bifrost for Windows"
VIAddVersionKey "LegalCopyright" "Bifrost for Windows"
VIAddVersionKey "FileDescription" "Bifrost Installer"
VIAddVersionKey "FileVersion" "3.3.0.0"
VIAddVersionKey "ProductVersion" "3.3.0.0"

; Icons
!define MUI_ICON "app.ico"
!define MUI_UNICON "app.ico"
!define MUI_ABORTWARNING

; Interface Settings
!define MUI_HEADERIMAGE
!define MUI_FINISHPAGE_RUN "$INSTDIR\Bifrost.exe"
!define MUI_FINISHPAGE_RUN_TEXT "اجرای Bifrost (Launch Bifrost)"

; Pages
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; Languages
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "Farsi"

; Installer Section
Section "Bifrost Core" SecCore
  ; Kill running Bifrost process before upgrading files to ensure clean overwrite
  nsExec::Exec 'taskkill /F /IM Bifrost.exe'
  nsExec::Exec 'taskkill /F /IM Bifrost-Console.exe'
  Sleep 400

  SetOutPath "$INSTDIR"
  
  ; Clean up any legacy leftover files from older versions
  Delete "$INSTDIR\worker.js"
  Delete "$INSTDIR\README.md"
  Delete "$INSTDIR\app.ico"
  Delete "$INSTDIR\Bifrost-Console.exe"
  Delete "$SMPROGRAMS\Bifrost\Bifrost (Console).lnk"

  ; Write ONLY the single main standalone binary
  File "dist/Bifrost.exe"

  ; Create Uninstaller
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  ; Registry for Settings & Install Directory
  WriteRegStr HKLM "Software\Bifrost" "InstallDir" "$INSTDIR"

  ; Windows Add/Remove Programs (Control Panel & Settings) Registration
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost" "DisplayName" "Bifrost for Windows"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost" "DisplayVersion" "3.3.0"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost" "Publisher" "Bifrost for Windows"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost" "DisplayIcon" "$INSTDIR\Bifrost.exe,0"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost" "NoModify" 1
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost" "NoRepair" 1

  ; Shortcuts
  CreateDirectory "$SMPROGRAMS\Bifrost"
  CreateShortcut "$SMPROGRAMS\Bifrost\Bifrost.lnk" "$INSTDIR\Bifrost.exe" "" "$INSTDIR\Bifrost.exe" 0
  CreateShortcut "$SMPROGRAMS\Bifrost\Uninstall Bifrost.lnk" "$INSTDIR\Uninstall.exe" "" "$INSTDIR\Uninstall.exe" 0
  CreateShortcut "$DESKTOP\Bifrost.lnk" "$INSTDIR\Bifrost.exe" "" "$INSTDIR\Bifrost.exe" 0
SectionEnd

; Uninstaller Section
Section "Uninstall"
  ; Kill running Bifrost process
  nsExec::Exec 'taskkill /F /IM Bifrost.exe'
  nsExec::Exec 'taskkill /F /IM Bifrost-Console.exe'
  Sleep 300

  ; Delete Files
  Delete "$INSTDIR\Bifrost.exe"
  Delete "$INSTDIR\Bifrost-Console.exe"
  Delete "$INSTDIR\worker.js"
  Delete "$INSTDIR\README.md"
  Delete "$INSTDIR\app.ico"
  Delete "$INSTDIR\Uninstall.exe"

  ; Delete Shortcuts
  Delete "$DESKTOP\Bifrost.lnk"
  Delete "$SMPROGRAMS\Bifrost\Bifrost.lnk"
  Delete "$SMPROGRAMS\Bifrost\Bifrost (Console).lnk"
  Delete "$SMPROGRAMS\Bifrost\Uninstall Bifrost.lnk"
  RMDir "$SMPROGRAMS\Bifrost"

  ; Remove Install Directory
  RMDir "$INSTDIR"

  ; Remove Registry Keys
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost"
  DeleteRegKey HKLM "Software\Bifrost"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Bifrost"
  DeleteRegKey HKCU "Software\Bifrost"
SectionEnd
