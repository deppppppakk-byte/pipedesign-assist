WIRELESSKEY 4.4 NATIVE PREVIEW
===============================

This is the first native-architecture build of WirelessKey.

ANDROID
-------
- Native Android Views: no WebView and no HTML UI.
- Native QWERTY keyboard, function row, modifiers and key repeat.
- Native multi-touch precision touchpad.
- Native Numpad, Media and contextual Shortcuts panels.
- Encrypted WSS connection with certificate pinning.
- LAN discovery and trusted-PC reconnect.
- Live active-app profile and latency display.

WINDOWS
-------
- Native C#/.NET 8 receiver.
- Native WinForms status window and system tray.
- Native Windows SendInput keyboard/mouse injection.
- TLS/WSS server with persistent local certificate.
- LAN discovery on UDP 8766.
- Pairing, trusted tokens and incorrect-code rate limiting.
- Active-app detection for AutoCAD, Revit, Excel, PowerPoint, Word,
  VS Code and browsers.
- Start-with-Windows toggle in the native receiver.

QUICK START
-----------
1. Extract the Windows ZIP.
2. Run WirelessKeyReceiver.exe.
3. Allow Private-network access if Windows Firewall asks.
4. If discovery is blocked, run ALLOW_FIREWALL_PRIVATE.bat as Administrator once.
5. Install the WirelessKey 4.0 Android APK.
6. On Android tap Find PC, select the PC, enter the receiver's 6-digit code,
   and tap Connect.

This is a v4 architecture preview. Real phone-to-PC latency, gesture feel,
screen-size ergonomics and reconnect-after-sleep still require device testing.


V4.4 PHASE 2
------------
- QR pairing from the native Windows receiver
- Remembered PCs on Android
- Two-way text clipboard bridge
- Persistent editable macro buttons
