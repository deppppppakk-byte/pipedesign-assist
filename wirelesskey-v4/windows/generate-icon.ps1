$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$out = Join-Path $PSScriptRoot "WirelessKey.ico"
$bitmap = New-Object System.Drawing.Bitmap 64,64
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([System.Drawing.Color]::FromArgb(9,12,17))

$deck = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(28,35,44))
$key = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(72,84,100))
$accent = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(76,132,255))

$graphics.FillRectangle($deck, 5, 9, 54, 46)
for ($row=0; $row -lt 3; $row++) {
  for ($col=0; $col -lt 5; $col++) {
    $graphics.FillRectangle($key, 11 + $col*9, 16 + $row*9, 6, 5)
  }
}
$graphics.FillRectangle($accent, 16, 43, 32, 6)

$hicon = $bitmap.GetHicon()
$icon = [System.Drawing.Icon]::FromHandle($hicon)
$stream = [System.IO.File]::Open($out, [System.IO.FileMode]::Create)
$icon.Save($stream)
$stream.Dispose()
$icon.Dispose()
$graphics.Dispose()
$bitmap.Dispose()

Write-Host "Generated $out"