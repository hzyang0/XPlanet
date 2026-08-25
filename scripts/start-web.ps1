$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Start-Process python -ArgumentList '-m','http.server','4173','--directory',(Join-Path $root 'xplanet-seckill-web') -WorkingDirectory $root -WindowStyle Hidden
Write-Host 'Flash-sale console: http://localhost:4173' -ForegroundColor Green
