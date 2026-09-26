WIRELESSKEY 3.0 - WINDOWS RECEIVER
===================================

WirelessKey 3.0 is a local Android-to-Windows keyboard and precision
touchpad system. The v3 connection uses encrypted WSS/TLS traffic and
pins the receiver certificate discovered on your LAN.

QUICK START
-----------
1. Put Android and Windows on the same private Wi-Fi/LAN.
2. Extract this ZIP.
3. Double-click WirelessKeyReceiver.exe.
4. If Windows Firewall asks, allow PRIVATE networks.
5. On Android open WirelessKey 3.0.
6. Tap Find PC.
7. Select this computer.
8. Enter the 6-digit pairing code shown in the receiver window.
9. Tap Connect.

IMPORTANT
---------
For first secure pairing, use Find PC once. Discovery gives the Android
app the receiver certificate fingerprint that is pinned for future
encrypted reconnects.

V3 FEATURES
-----------
- TLS-encrypted persistent WebSocket connection
- Certificate pinning after LAN discovery
- Automatic reconnect
- Live latency display
- Trusted-device tokens
- Expiring 6-digit pairing code
- Incorrect-code rate limiting
- Automatic TCP port fallback (8765-8775)
- Windows tray receiver
- Pairing-code regeneration
- Revoke trusted phones
- Start with Windows support
- Active application detection
- AutoCAD / Revit / Excel / PowerPoint / Word / VS Code / browser profiles
- Precision pointer movement
- Vertical + horizontal two-finger scrolling
- Two-finger right click
- Pinch zoom
- Three-finger app/task gestures
- Four-finger virtual desktop switching
- Keyboard, numpad, media and shortcuts modes

START WITH WINDOWS
------------------
Run START_WITH_WINDOWS.bat once.
WirelessKeyReceiver.exe will start hidden in the system tray when you
sign in. Use the tray icon to show the receiver window.

Run REMOVE_STARTUP.bat to disable this behavior.

IF FIND PC SHOWS NOTHING
------------------------
1. Keep WirelessKeyReceiver.exe running.
2. Confirm both devices are on the same Wi-Fi.
3. Set the Windows network profile to Private.
4. Run ALLOW_FIREWALL_PRIVATE.bat as Administrator once.
5. Tap Find PC again.

SECURITY
--------
- Input traffic is encrypted with TLS.
- Android verifies the receiver certificate fingerprint learned during
  local discovery.
- Pairing code expires after 30 minutes.
- Trusted phones reconnect with a local token.
- Five incorrect pairing attempts in a minute trigger rate limiting.
- Firewall helper creates Private-network rules only.
- No cloud or internet control is required.

FILES
-----
WirelessKeyReceiver.exe
ALLOW_FIREWALL_PRIVATE.bat
START_WITH_WINDOWS.bat
REMOVE_STARTUP.bat
README.txt
