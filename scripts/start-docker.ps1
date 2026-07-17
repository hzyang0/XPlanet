#Requires -Version 5.1
<#
.SYNOPSIS
  构建并启动 XPlanet 全 Docker 环境。

.DESCRIPTION
  自动切换 RocketMQ 为容器网络广播地址、启动基础设施、执行数据库迁移、构建应用镜像，
  并等待五个应用健康。Docker 模式只向宿主机暴露 Gateway 8080；其余服务只在容器网络中可达。
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
if ([string]::IsNullOrWhiteSpace($env:AGENT_INTERNAL_TOKEN)) {
    $env:AGENT_INTERNAL_TOKEN = $env:TOKEN_SECRET
}
if ($env:AGENT_INTERNAL_TOKEN.Length -lt 32) {
    Write-Error 'AGENT_INTERNAL_TOKEN 至少需要32字符。'
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

Write-Host ">>> 构建并启动五个应用容器和 Agent" -ForegroundColor Cyan
docker compose -f docker/docker-compose-app.yml up -d --build

$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 2
    $down = @()
    try {
        if ((Invoke-RestMethod "http://localhost:8080/actuator/health" -TimeoutSec 3).status -ne "UP") {
            $down += "gateway"
        }
    } catch {
        $down += "gateway"
    }
    foreach ($service in "article:8081", "interaction:8082", "user:8083", "ai:8084") {
        try {
            $status = docker exec xp-agent python -c `
                "import json,urllib.request; print(json.load(urllib.request.urlopen('http://$service/actuator/health',timeout=2))['status'])" 2>$null
            if (($status | Out-String).Trim() -ne "UP") {
                $down += $service
            }
        } catch {
            $down += $service
        }
    }
} while ($down.Count -gt 0 -and (Get-Date) -lt $deadline)

if ($down.Count -gt 0) {
    docker compose -f docker/docker-compose-app.yml ps
    docker compose -f docker/docker-compose-app.yml logs --tail 100
    throw "应用容器未在3分钟内全部健康：$($down -join ',')"
}

$agentHealth = docker inspect -f "{{.State.Health.Status}}" xp-agent 2>$null
if ($agentHealth -ne "healthy") {
    docker logs xp-agent --tail 100
    throw "Agent 容器健康状态异常：$agentHealth"
}

docker compose -f docker/docker-compose-infra.yml ps
docker compose -f docker/docker-compose-app.yml ps
Write-Host ">>> XPlanet 全 Docker 环境已就绪" -ForegroundColor Green
Write-Host ">>> 统一入口：http://localhost:8080" -ForegroundColor Green
