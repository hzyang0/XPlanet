#Requires -Version 5.1
<#
.SYNOPSIS
  Pause RocketMQ while creating an AI task, then verify Outbox recovery.
#>
param(
    [string]$Username = "alice",
    [string]$Password = "password",
    [string]$GatewayBaseUrl = "http://localhost:8080",
    [string]$MysqlContainer = "xp-mysql",
    [string]$BrokerContainer = "xp-rmq-broker",
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root
$BrokerRestored = $false

function Invoke-SqlScalar([string]$Sql) {
    $value = docker exec -e MYSQL_PWD=root123 $MysqlContainer mysql -N -B -uroot xplanet -e $Sql
    if ($LASTEXITCODE -ne 0) { throw "SQL failed: $Sql" }
    return ($value | Out-String).Trim()
}

function Wait-Until([scriptblock]$Condition, [string]$Description) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for ${Description} (${TimeoutSeconds}s)"
}

function Restore-Broker {
    docker start $BrokerContainer | Out-Host
    Wait-Until { (docker inspect -f '{{.State.Running}}' $BrokerContainer 2>$null) -eq 'true' } "Broker to run"
    $script:BrokerRestored = $true
}

try {
    Write-Host ">>> Login and pause RocketMQ Broker" -ForegroundColor Cyan
    $loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
    $login = Invoke-RestMethod -Method Post -Uri "$GatewayBaseUrl/api/user/login" `
        -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 10
    if ($login.code -ne 0) { throw "Login failed" }
    docker stop -t 10 $BrokerContainer | Out-Host

    $headers = @{
        Authorization = "Bearer $($login.data.token)"
        "Idempotency-Key" = "mq-pause-$([Guid]::NewGuid().ToString('N'))"
    }
    $body = @{
        question = "Verify AI outbox delivery after RocketMQ broker recovery"
        maxSources = 2
        maxToolCalls = 2
        maxTokens = 4000
        deadlineSeconds = 120
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Method Post -Uri "$GatewayBaseUrl/api/ai/tasks" `
        -Headers $headers -ContentType "application/json; charset=utf-8" -Body $body -TimeoutSec 10
    if ($created.code -ne 0) { throw "Creating the MQ pause test task failed" }
    $taskId = [long]$created.data.id

    Wait-Until {
        $row = Invoke-SqlScalar "SELECT CONCAT(status,':',retry_count) FROM ai_outbox WHERE aggregate_id=$taskId AND event_type='AI_TASK_REQUESTED' ORDER BY id DESC LIMIT 1;"
        $row -match '^[01]:[1-9][0-9]*$'
    } "Outbox retry after broker failure"
    $pausedState = Invoke-SqlScalar "SELECT CONCAT(status,':',retry_count) FROM ai_outbox WHERE aggregate_id=$taskId AND event_type='AI_TASK_REQUESTED' ORDER BY id DESC LIMIT 1;"

    Write-Host ">>> Restore Broker and wait for Outbox recovery" -ForegroundColor Cyan
    Restore-Broker
    Wait-Until {
        (Invoke-SqlScalar "SELECT status FROM ai_outbox WHERE aggregate_id=$taskId AND event_type='AI_TASK_REQUESTED' ORDER BY id DESC LIMIT 1;") -eq '2'
    } "Outbox delivery"
    Wait-Until {
        (Invoke-SqlScalar "SELECT status FROM ai_task WHERE id=$taskId;") -eq 'WAITING_REVIEW'
    } "task to reach WAITING_REVIEW"

    [pscustomobject]@{
        TaskId = $taskId
        PausedOutboxState = $pausedState
        FinalOutboxStatus = 2
        FinalTaskStatus = 'WAITING_REVIEW'
        BrokerRestored = $BrokerRestored
    } | Format-List
}
finally {
    if (-not $BrokerRestored) {
        Restore-Broker
    }
}
