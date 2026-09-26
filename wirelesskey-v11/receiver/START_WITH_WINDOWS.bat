@echo off
title WirelessKey 3.0 - Start with Windows
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "WirelessKeyReceiver" /t REG_SZ /d "\"%~dp0WirelessKeyReceiver.exe\" --hidden" /f
echo.
echo WirelessKey will now start in the system tray when you sign in to Windows.
pause
