@echo off
title WirelessKey 3.0 - Remove from Windows Startup
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "WirelessKeyReceiver" /f
echo.
echo WirelessKey startup entry removed.
pause
