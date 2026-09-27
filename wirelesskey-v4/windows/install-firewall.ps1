param([Parameter(Mandatory=$true)][string]$ProgramPath)

$ErrorActionPreference = "Stop"

$tcpName = "WirelessKey Receiver TCP"
$udpName = "WirelessKey Discovery UDP"

Get-NetFirewallRule -DisplayName $tcpName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
Get-NetFirewallRule -DisplayName $udpName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue

New-NetFirewallRule -DisplayName $tcpName -Direction Inbound -Action Allow -Profile Private -Protocol TCP -LocalPort "8765-8775" -Program $ProgramPath -Enabled True | Out-Null
New-NetFirewallRule -DisplayName $udpName -Direction Inbound -Action Allow -Profile Private -Protocol UDP -LocalPort 8766 -Program $ProgramPath -Enabled True | Out-Null

if (-not (Get-NetFirewallRule -DisplayName $tcpName -ErrorAction SilentlyContinue)) { throw "TCP firewall rule was not created." }
if (-not (Get-NetFirewallRule -DisplayName $udpName -ErrorAction SilentlyContinue)) { throw "UDP firewall rule was not created." }