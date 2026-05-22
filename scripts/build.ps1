#Requires -Version 5.1
<#
.SYNOPSIS
  Maven 打包 XPlanet（跳过测试）

.EXAMPLE
  cd D:\path\to\xplanet
  .\scripts\build.ps1
#>
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 mvn。请安装 Maven 3.9+ 并加入 PATH，或使用 IDE 自带 Maven。"
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 java。请安装 JDK 17 并加入 PATH。"
}

Write-Host '>>> mvn clean package -DskipTests' -ForegroundColor Cyan
mvn clean package -DskipTests
Write-Host '>>> 构建完成' -ForegroundColor Green
