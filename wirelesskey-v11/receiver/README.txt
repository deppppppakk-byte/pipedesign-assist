WIRELESSKEY v1.1 - WINDOWS RECEIVER
===================================

1. Put your phone and Windows PC on the same Wi-Fi.
2. Double-click WirelessKeyReceiver.exe.
3. If Windows Firewall asks, allow access on PRIVATE networks.
4. The receiver window shows:
   - Laptop IP
   - 6-digit pairing code
5. Open WirelessKey v1.1 on Android.
6. Enter the Laptop IP and 6-digit code.
7. Tap Test. It should show "PC receiver found".
8. Use the built-in keyboard and mini touchpad.

If Test says "PC unreachable":
- Make sure WirelessKeyReceiver.exe is still open.
- Confirm both devices are on the same Wi-Fi.
- Confirm Windows network profile is Private.
- Run ALLOW_FIREWALL_PRIVATE.bat as Administrator once.
- Some guest/public Wi-Fi networks block devices from talking to each other.

Security:
- The receiver accepts commands only with the current random pairing code.
- A new code is generated every time the receiver starts.
- The optional firewall helper creates a rule for PRIVATE networks only.
