#Requires -Version 5.1
<#
.SYNOPSIS
  启动 XPlanet 依赖基础设施（MySQL / Redis / RocketMQ）

.DESCRIPTION
  需在仓库根目录执行，且已安装 Docker Desktop 并处于运行状态。
  自动使用适合宿主机 JVM 的 RocketMQ broker 地址，并在 MySQL 就绪后执行 Flyway。

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
$env:ROCKETMQ_BROKER_CONFIG = "broker-host.conf"
docker compose -f docker/docker-compose-infra.yml up -d
docker compose -f docker/docker-compose-infra.yml up -d --force-recreate rocketmq-broker

Write-Host '>>> 等待 MySQL 就绪（最多约 2 分钟）...' -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(2)
$mysqlReady = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = docker exec xp-mysql mysqladmin ping -h localhost -uroot -proot123 2>$null
        if ($LASTEXITCODE -eq 0) {
            $mysqlReady = $true
            Write-Host '>>> MySQL 已就绪' -ForegroundColor Green
            break
        }
    } catch { }
    Start-Sleep -Seconds 3
}
if (-not $mysqlReady) {
    throw 'MySQL 未在2分钟内就绪'
}

& (Join-Path $PSScriptRoot "migrate-db.ps1")

Write-Host '>>> 容器状态:' -ForegroundColor Cyan
docker compose -f docker/docker-compose-infra.yml ps

Write-Host @"

下一步（本机跑应用）:
  .\scripts\build.ps1
  .\scripts\start-local.ps1

全 Docker 演示模式请改用:
  .\scripts\start-docker.ps1
"@ -ForegroundColor Yellow
