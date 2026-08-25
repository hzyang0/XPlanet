param([string]$Container = 'xp-seckill-mysql')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$migration = Join-Path $root 'sql\commerce-migration.sql'
docker cp $migration "$Container`:/tmp/commerce-migration.sql"
docker exec $Container sh -c 'mysql -uroot -proot123 xplanet < /tmp/commerce-migration.sql'
Write-Host 'Commerce schema applied.'
