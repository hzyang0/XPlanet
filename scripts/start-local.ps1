#Requires -Version 5.1
<#
.SYNOPSIS
  在本机启动 3 个 Spring Boot 服务（各开一个独立 PowerShell 窗口）

.DESCRIPTION
  请先用 docker compose -f docker/docker-compose-infra.yml up -d 启动中间件,
  再执行本脚本。默认连接 localhost 的 MySQL(3306)、Redis(6379)、RocketMQ(9876)。

.EXAMPLE
  cd D:\path\to\xplanet
  .\scripts\start-local.ps1
#>
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 mvn。请先安装 Maven 并加入 PATH。"
}

$env:MYSQL_HOST = "localhost"
$env:MYSQL_USER = "root"
$env:MYSQL_PWD = "root123"
$env:REDIS_HOST = "localhost"
$env:ROCKETMQ_NS = "localhost:9876"

Write-Host '>>> mvn install -DskipTests (安装 com.xplanet 公共模块到本地仓库)...' -ForegroundColor Cyan
& mvn install -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Maven install 失败,请先修复编译错误。'
}
Write-Host '>>> 启动各 Spring Boot 服务（新窗口）...' -ForegroundColor Cyan

$modules = @(
    @{ Name = "xplanet-user";         Port = 8083 },
    @{ Name = "xplanet-article";      Port = 8081 },
    @{ Name = "xplanet-interaction";  Port = 8082 }
)

foreach ($m in $modules) {
    $name = $m.Name
    $port = $m.Port
    $runner = Join-Path $env:TEMP ("xplanet-run-{0}.ps1" -f $name)
    $nl = [Environment]::NewLine
    $scriptBody = @(
        ('Set-Location ''{0}''' -f $Root)
        '$env:MYSQL_HOST=''localhost''; $env:MYSQL_USER=''root''; $env:MYSQL_PWD=''root123'''
        '$env:REDIS_HOST=''localhost''; $env:ROCKETMQ_NS=''localhost:9876'''
        ('Write-Host ''Starting {0} ({1})...'' -ForegroundColor Cyan' -f $name, $port)
        ('mvn spring-boot:run -pl {0}' -f $name)
    ) -join $nl
    Set-Content -LiteralPath $runner -Value $scriptBody -Encoding UTF8
    Start-Process powershell -ArgumentList '-NoExit', '-File', $runner | Out-Null
    Start-Sleep -Milliseconds 800
}

$doneMsg = @'

已尝试在新窗口启动各服务。请等待各窗口出现 Started *Application 后再验证:

  article:      http://localhost:8081/actuator/health
  interaction:  http://localhost:8082/actuator/health
  user:         http://localhost:8083/actuator/health
  演示页:       打开 xplanet-web/index.html（article 指向 8081,like 指向 8082）
'@
Write-Host $doneMsg -ForegroundColor Green
