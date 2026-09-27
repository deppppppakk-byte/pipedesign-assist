param([Parameter(Mandatory=$true)][string]$ProgramPath)

$ErrorActionPreference = "Stop"
$log = Join-Path $env:TEMP "wirelesskey-firewall-install.log"
Remove-Item $log -Force -ErrorAction SilentlyContinue

function Write-Log([string]$Message) {
    Add-Content -Path $log -Value ("[{0}] {1}" -f (Get-Date -Format o), $Message)
}

try {
    Write-Log "Starting WirelessKey firewall setup."
    Write-Log ("Identity: " + [System.Security.Principal.WindowsIdentity]::GetCurrent().Name)
    Write-Log ("ProgramPath: " + $ProgramPath)
    Write-Log ("Program exists: " + (Test-Path $ProgramPath))

    $service = Get-Service -Name MpsSvc -ErrorAction SilentlyContinue
    if ($service) { Write-Log ("MpsSvc status: " + $service.Status) } else { Write-Log "MpsSvc service not found." }

    $tcpName = "WirelessKey Receiver TCP"
    $udpName = "WirelessKey Discovery UDP"

    Get-NetFirewallRule -DisplayName $tcpName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
    Get-NetFirewallRule -DisplayName $udpName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue

    New-NetFirewallRule -DisplayName $tcpName -Direction Inbound -Action Allow -Profile Private -Protocol TCP -LocalPort "8765-8775" -Program $ProgramPath -Enabled True | Out-Null
    New-NetFirewallRule -DisplayName $udpName -Direction Inbound -Action Allow -Profile Private -Protocol UDP -LocalPort 8766 -Program $ProgramPath -Enabled True | Out-Null

    $tcp = Get-NetFirewallRule -DisplayName $tcpName -ErrorAction SilentlyContinue
    $udp = Get-NetFirewallRule -DisplayName $udpName -ErrorAction SilentlyContinue
    Write-Log ("TCP rule present: " + [bool]$tcp)
    Write-Log ("UDP rule present: " + [bool]$udp)

    if (-not $tcp) { throw "TCP firewall rule was not created." }
    if (-not $udp) { throw "UDP firewall rule was not created." }

    Write-Log "WirelessKey firewall setup completed successfully."
    exit 0
} catch {
    Write-Log ("ERROR: " + $_.Exception.Message)
    Write-Log ($_ | Out-String)
    exit 1
}