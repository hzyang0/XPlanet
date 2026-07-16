#Requires -Version 5.1
<#
.SYNOPSIS
  使用 Flyway 将当前 MySQL 数据库迁移到仓库版本。

.DESCRIPTION
  现有 V4 数据库首次执行时建立 Flyway baseline；后续从 V5 开始自动执行版本迁移。
  新数据卷会先执行 sql/init.sql，因此同样从 V4 baseline 开始。
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
