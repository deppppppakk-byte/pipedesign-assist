# WirelessKey v4.2 Native Architecture

Phase 1 replaces the WebView Android client and Python/PyInstaller Windows receiver with native implementations.

## Native Android
- Android Views keyboard and controls
- Dedicated native multi-touch touchpad
- Secure WSS transport with certificate pinning
- LAN discovery, trusted-PC reconnect, latency and active-app context

## Native Windows
- .NET 8 / WinForms receiver
- Native Windows SendInput injection
- Kestrel TLS WebSocket server
- UDP discovery
- Tray/status UI and Start-with-Windows support
- Active-app profile reporting

## Next phases
- QR pairing and multi-PC switcher
- Clipboard bridge and file drop
- User-editable macros and gesture mapping
- USB transport
- Bluetooth HID research/implementation where device support allows
- MSI/MSIX installer, persistent production signing and signed updates


## v4.2 Phase 2
- QR pairing with PC address, certificate fingerprint and temporary code
- Remembered/multi-PC selector
- Phone-to-PC and PC-to-phone text clipboard
- Editable persistent macro slots
