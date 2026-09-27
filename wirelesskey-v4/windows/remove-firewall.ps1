$ErrorActionPreference = "SilentlyContinue"
Get-NetFirewallRule -DisplayName "WirelessKey Receiver TCP" -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
Get-NetFirewallRule -DisplayName "WirelessKey Discovery UDP" -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue