WIRELESSKEY 2.0 - WINDOWS RECEIVER
===================================

WirelessKey 2.0 uses a persistent WebSocket connection for low-latency
keyboard and precision-touchpad input. No cloud server is required.

QUICK START
-----------
1. Put the Android device and Windows PC on the same Wi-Fi/LAN.
2. Double-click WirelessKeyReceiver.exe.
3. If Windows Firewall asks, allow access on PRIVATE networks.
4. Open WirelessKey 2.0 on Android.
5. Tap "Find PC".
6. Select this computer.
7. Enter the 6-digit pairing code shown in the receiver window.
8. Tap Connect.

The phone receives a trusted-device token after successful pairing, so it
can reconnect automatically after Wi-Fi interruptions or receiver restarts.

FEATURES
--------
- Persistent low-latency WebSocket connection
- Auto reconnect
- LAN PC discovery
- Trusted-device tokens
- 6-digit pairing
- Rate limiting for incorrect codes
- Automatic TCP port fallback (8765 through 8775)
- Keyboard, mouse, scrolling and double-click support
- Local-network-only design by default

IF "FIND PC" SHOWS NOTHING
--------------------------
1. Keep WirelessKeyReceiver.exe open.
2. Confirm phone and PC are on the same Wi-Fi.
3. Make sure Windows network profile is Private.
4. Right-click ALLOW_FIREWALL_PRIVATE.bat and choose Run as administrator.
5. Try Find PC again.
6. As a fallback, enter the PC IP shown in the receiver window manually.

SECURITY
--------
- Pairing code expires after 30 minutes.
- Trusted tokens are stored locally under the user's AppData folder.
- Five incorrect pairing attempts in one minute temporarily rate-limit
  further code attempts from that address.
- Firewall helper opens only Private-network rules.

KEEP WirelessKeyReceiver.exe OPEN while using the Android app.
