@echo off
title WirelessKey - Allow Private Network
echo This optional helper adds a Windows Firewall rule for WirelessKeyReceiver.exe on PRIVATE networks only.
echo.
net session >nul 2>&1
if not %errorlevel%==0 (
  echo Please right-click this file and choose "Run as administrator".
  pause
  exit /b 1
)
netsh advfirewall firewall delete rule name="WirelessKey Receiver" >nul 2>&1
netsh advfirewall firewall add rule name="WirelessKey Receiver" dir=in action=allow program="%~dp0WirelessKeyReceiver.exe" enable=yes profile=private protocol=TCP localport=8765
echo.
echo Private-network firewall rule created.
pause
