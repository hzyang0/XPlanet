$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Write-Host 'Start Docker infrastructure first: docker compose -f docker/docker-compose-infra.yml up -d'
Start-Process powershell -ArgumentList '-NoExit', '-Command', "Set-Location '$root'; mvn -pl xplanet-user -am spring-boot:run"
Start-Sleep -Seconds 3
Start-Process powershell -ArgumentList '-NoExit', '-Command', "Set-Location '$root'; mvn -pl xplanet-seckill -am spring-boot:run"
