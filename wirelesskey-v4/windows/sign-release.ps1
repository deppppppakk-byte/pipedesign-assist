param(
    [Parameter(Mandatory=$true)][string]$CertificatePath,
    [Parameter(Mandatory=$true)][string]$TimestampUrl,
    [string]$ReceiverPath = ".\publish\WirelessKeyReceiver.exe",
    [string]$SetupPath = ".\installer\output\WirelessKeySetup-4.5.exe"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $CertificatePath)) {
    throw "Code-signing certificate file not found: $CertificatePath"
}

$password = $env:WIRELESSKEY_SIGNING_PASSWORD
if ([string]::IsNullOrWhiteSpace($password)) {
    throw "Set WIRELESSKEY_SIGNING_PASSWORD in the environment. Do not commit the password."
}

$signtool = Get-Command signtool.exe -ErrorAction SilentlyContinue
if (-not $signtool) {
    throw "signtool.exe was not found. Install the Windows SDK signing tools."
}

function Sign-File([string]$Path) {
    if (-not (Test-Path $Path)) {
        throw "File to sign not found: $Path"
    }

    & $signtool.Source sign /fd SHA256 /f $CertificatePath /p $password /tr $TimestampUrl /td SHA256 $Path
    if ($LASTEXITCODE -ne 0) { throw "Signing failed for $Path" }

    & $signtool.Source verify /pa /v $Path
    if ($LASTEXITCODE -ne 0) { throw "Signature verification failed for $Path" }
}

Sign-File $ReceiverPath
Sign-File $SetupPath

Write-Host "WirelessKey release binaries signed and verified."