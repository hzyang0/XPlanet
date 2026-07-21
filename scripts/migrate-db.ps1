#Requires -Version 5.1
<#
.SYNOPSIS
  使用 Flyway 将当前 MySQL 数据库迁移到仓库版本。

.DESCRIPTION
  空数据库从 V4 baseline migration 开始创建完整结构；现有 V4 数据库首次执行时
  建立 Flyway baseline，随后统一执行 V5+ 增量迁移。
#>
param(
    [string]$MysqlHost = "localhost",
    [int]$MysqlPort = 3306,
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "root123",
    [string]$Database = "xplanet"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 mvn，无法执行 Flyway 迁移。"
}

$url = "jdbc:mysql://${MysqlHost}:${MysqlPort}/${Database}"
Write-Host ">>> Flyway migrate: ${MysqlHost}:${MysqlPort}/${Database}" -ForegroundColor Cyan
& mvn -B -ntp -N flyway:migrate `
    "-Dflyway.url=$url" `
    "-Dflyway.user=$MysqlUser" `
    "-Dflyway.password=$MysqlPassword" `
    "-Dflyway.baselineOnMigrate=true" `
    "-Dflyway.baselineVersion=4" `
    "-Dflyway.baselineDescription=XPlanet schema through V004"
if ($LASTEXITCODE -ne 0) {
    throw "Flyway 数据库迁移失败"
}
Write-Host ">>> Flyway 数据库迁移完成" -ForegroundColor Green
