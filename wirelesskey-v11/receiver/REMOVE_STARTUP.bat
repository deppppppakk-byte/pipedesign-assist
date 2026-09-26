@echo off
title WirelessKey 3.1 - Remove from Windows Startup
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "WirelessKeyReceiver" /f >nul 2>&1
echo.
echo WirelessKey startup entry removed.
if /I not "%~1"=="--silent" pause
