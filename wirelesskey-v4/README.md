# WirelessKey v4.0 Native Architecture

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
