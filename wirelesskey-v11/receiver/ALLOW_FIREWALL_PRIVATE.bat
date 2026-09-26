@echo off
title WirelessKey 2.0 - Private Network Firewall Setup
echo WirelessKey 2.0 needs local-network access for:
echo   TCP 8765-8775  - keyboard/mouse WebSocket
echo   UDP 8766       - automatic PC discovery
echo.
net session >nul 2>&1
if not %errorlevel%==0 (
  echo Please right-click this file and choose "Run as administrator".
  pause
  exit /b 1
)
netsh advfirewall firewall delete rule name="WirelessKey 2.0 TCP" >nul 2>&1
netsh advfirewall firewall delete rule name="WirelessKey 2.0 Discovery" >nul 2>&1
netsh advfirewall firewall add rule name="WirelessKey 2.0 TCP" dir=in action=allow program="%~dp0WirelessKeyReceiver.exe" enable=yes profile=private protocol=TCP localport=8765-8775
netsh advfirewall firewall add rule name="WirelessKey 2.0 Discovery" dir=in action=allow program="%~dp0WirelessKeyReceiver.exe" enable=yes profile=private protocol=UDP localport=8766
echo.
echo Private-network firewall rules created successfully.
pause
