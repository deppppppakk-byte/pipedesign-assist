@echo off
title WirelessKey 4.0 - Private Network Firewall
echo WirelessKey 4.0 needs Private-network access for:
echo   TCP 8765-8775  - encrypted WSS input
echo   UDP 8766       - PC discovery
echo.
net session >nul 2>&1
if not %errorlevel%==0 (
  echo Right-click this file and choose "Run as administrator".
  pause
  exit /b 1
)
netsh advfirewall firewall delete rule name="WirelessKey 4.0 TCP" >nul 2>&1
netsh advfirewall firewall delete rule name="WirelessKey 4.0 Discovery" >nul 2>&1
netsh advfirewall firewall add rule name="WirelessKey 4.0 TCP" dir=in action=allow program="%~dp0WirelessKeyReceiver.exe" enable=yes profile=private protocol=TCP localport=8765-8775
netsh advfirewall firewall add rule name="WirelessKey 4.0 Discovery" dir=in action=allow program="%~dp0WirelessKeyReceiver.exe" enable=yes profile=private protocol=UDP localport=8766
echo.
echo WirelessKey 4.0 Private-network rules created.
pause
