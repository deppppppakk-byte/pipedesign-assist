@echo off
title WirelessKey 3.1 - Start with Windows
set "APP=%~dp0WirelessKeyReceiver.exe"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$app=$env:APP; $value='"' + $app + '" --hidden'; New-Item -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Force | Out-Null; Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'WirelessKeyReceiver' -Value $value"
if errorlevel 1 (
  echo.
  echo Could not add WirelessKey to startup.
  if /I not "%~1"=="--silent" pause
  exit /b 1
)
echo.
echo WirelessKey will now start in the system tray when you sign in to Windows.
if /I not "%~1"=="--silent" pause
