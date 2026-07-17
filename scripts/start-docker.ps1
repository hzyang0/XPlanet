#Requires -Version 5.1
<#
.SYNOPSIS
  构建并启动 XPlanet 全 Docker 环境。

.DESCRIPTION
  自动切换 RocketMQ 为容器网络广播地址、启动基础设施、执行数据库迁移、构建应用镜像，
  并等待 Gateway 健康。若本机 8080 已被 IDE 或本地 JVM 占用，脚本会直接失败。
#>
param(
    [string]$DockerBaseRegistry = $(
        if ([string]::IsNullOrWhiteSpace($env:DOCKER_BASE_REGISTRY)) {
            "docker.io/library"
        } else {
            $env:DOCKER_BASE_REGISTRY
        }
    )
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 docker。"
}
if ([string]::IsNullOrWhiteSpace($env:TOKEN_SECRET) -or $env:TOKEN_SECRET.Length -lt 32) {
    Write-Error '请先设置至少32字符的 TOKEN_SECRET。'
}

$occupied = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -eq 8080 }
if ($occupied) {
    $ports = ($occupied.LocalPort | Sort-Object -Unique) -join ","
    throw "应用端口已被本机进程占用：$ports。请先停止本地 JVM。"
}

$env:ROCKETMQ_BROKER_CONFIG = "broker-docker.conf"
$env:DOCKER_BASE_REGISTRY = $DockerBaseRegistry
Write-Host ">>> 应用基础镜像仓库：$DockerBaseRegistry" -ForegroundColor Cyan
Write-Host ">>> 启动基础设施（全 Docker broker 地址）" -ForegroundColor Cyan
docker compose -f docker/docker-compose-infra.yml up -d
docker compose -f docker/docker-compose-infra.yml up -d --force-recreate rocketmq-broker

Write-Host ">>> 等待 MySQL healthy" -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(2)
do {
    Start-Sleep -Seconds 2
    $mysqlHealth = docker inspect -f "{{.State.Health.Status}}" xp-mysql 2>$null
} while ($mysqlHealth -ne "healthy" -and (Get-Date) -lt $deadline)
if ($mysqlHealth -ne "healthy") {
    throw "MySQL 未在2分钟内就绪"
}

& (Join-Path $PSScriptRoot "migrate-db.ps1")

Write-Host ">>> 构建并启动四个应用容器" -ForegroundColor Cyan
docker compose -f docker/docker-compose-app.yml up -d --build

$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 2
    $down = @()
    foreach ($port in 8080) {
        try {
            if ((Invoke-RestMethod "http://localhost:$port/actuator/health" -TimeoutSec 3).status -ne "UP") {
                $down += $port
            }
        } catch {
            $down += $port
        }
    }
} while ($down.Count -gt 0 -and (Get-Date) -lt $deadline)

if ($down.Count -gt 0) {
    docker compose -f docker/docker-compose-app.yml ps
    docker compose -f docker/docker-compose-app.yml logs --tail 100
    throw "应用容器未在3分钟内全部健康：$($down -join ',')"
}

docker compose -f docker/docker-compose-infra.yml ps
docker compose -f docker/docker-compose-app.yml ps
Write-Host ">>> XPlanet 全 Docker 环境已就绪" -ForegroundColor Green
