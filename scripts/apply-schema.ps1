# Applies the idempotent flash-sale schema to an already-created local MySQL Docker volume.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Get-Content -Raw (Join-Path $root 'sql/init.sql') | docker exec -i xp-mysql mysql -uroot -proot123
if ($LASTEXITCODE -ne 0) { throw 'Schema application failed. Start Docker infrastructure first.' }
Write-Host 'Flash-sale schema is ready.' -ForegroundColor Green
