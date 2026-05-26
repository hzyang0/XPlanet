#Requires -Version 5.1
<#
.SYNOPSIS
  启动 XPlanet 依赖基础设施（MySQL / Redis / RocketMQ）

.DESCRIPTION
  需在仓库根目录执行，且已安装 Docker Desktop 并处于运行状态。
  Grafana 映射到本机 3100，避免与常见前端 3000 端口冲突。

.EXAMPLE
  cd D:\path\to\xplanet
  .\scripts\setup-infra.ps1
#>
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 docker。请先安装 Docker Desktop 并确保在 PATH 中。"
}

Write-Host ('>>> docker compose (infra) from: ' + $Root) -ForegroundColor Cyan
docker compose -f docker/docker-compose-infra.yml up -d

Write-Host '>>> 等待 MySQL 就绪（最多约 2 分钟）...' -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(2)
while ((Get-Date) -lt $deadline) {
    try {
        $r = docker exec xp-mysql mysqladmin ping -h localhost -uroot -proot123 2>$null
        if ($LASTEXITCODE -eq 0) { Write-Host '>>> MySQL 已就绪' -ForegroundColor Green; break }
    } catch { }
    Start-Sleep -Seconds 3
}

Write-Host '>>> 容器状态:' -ForegroundColor Cyan
docker compose -f docker/docker-compose-infra.yml ps

Write-Host @"

下一步（本机跑应用）:
  .\scripts\build.ps1
  .\scripts\start-local.ps1

或 Docker 跑应用（需本机 infra 已起）:
  docker compose -f docker/docker-compose-app.yml up -d --build
"@ -ForegroundColor Yellow
