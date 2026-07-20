#Requires -Version 5.1
<#
.SYNOPSIS
  在持久化 EXECUTE_TOOL checkpoint 后强制退出 Agent，验证工具副作用不会在恢复时重复执行。

.DESCRIPTION
  运行前需完成全 Docker 启动，并设置与当前容器一致的 TOKEN_SECRET 和 AGENT_INTERNAL_TOKEN。
  脚本只临时重建 Agent 容器，结束时会关闭故障注入并恢复健康 Agent。
#>
param(
    [string]$Username = "alice",
    [string]$Password = "password",
    [string]$GatewayBaseUrl = "http://localhost:8080",
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root
$AgentRestored = $false

if ([string]::IsNullOrWhiteSpace($env:AGENT_INTERNAL_TOKEN)) {
    throw "请先设置与运行中服务一致的 AGENT_INTERNAL_TOKEN"
}
if ([string]::IsNullOrWhiteSpace($env:TOKEN_SECRET)) {
    throw "请先设置与运行中服务一致的 TOKEN_SECRET"
}

function Invoke-SqlScalar([string]$Sql) {
    $value = docker exec -e MYSQL_PWD=root123 xp-mysql mysql -N -B -uroot xplanet -e $Sql
    if ($LASTEXITCODE -ne 0) { throw "SQL 执行失败：$Sql" }
    return ($value | Out-String).Trim()
}

function Wait-Until([scriptblock]$Condition, [string]$Description) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "等待超时：$Description（${TimeoutSeconds}s）"
}

function Restore-Agent {
    $env:AGENT_CRASH_AFTER_NODE = ""
    docker compose -f docker/docker-compose-app.yml up -d --no-build --force-recreate agent | Out-Host
    Wait-Until {
        (docker inspect --format '{{.State.Health.Status}}' xp-agent 2>$null) -eq "healthy"
    } "恢复 Agent 健康"
    $script:AgentRestored = $true
}

try {
    Write-Host ">>> 开启 checkpoint 后进程退出故障注入" -ForegroundColor Cyan
    $env:AGENT_CRASH_AFTER_NODE = "EXECUTE_TOOL"
    docker compose -f docker/docker-compose-app.yml up -d --no-build --force-recreate agent | Out-Host
    Wait-Until {
        (docker inspect --format '{{.State.Health.Status}}' xp-agent 2>$null) -eq "healthy"
    } "故障注入 Agent 健康"

    $loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
    $login = Invoke-RestMethod -Method Post -Uri "$GatewayBaseUrl/api/user/login" `
        -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 10
    if ($login.code -ne 0) { throw "登录失败" }
    $headers = @{
        Authorization = "Bearer $($login.data.token)"
        "Idempotency-Key" = "recovery-$([Guid]::NewGuid().ToString('N'))"
    }
    $body = @{
        question = "Verify Agent checkpoint crash recovery"
        maxSources = 3
        maxToolCalls = 3
        maxTokens = 4000
        deadlineSeconds = 120
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Method Post -Uri "$GatewayBaseUrl/api/ai/tasks" `
        -Headers $headers -ContentType "application/json; charset=utf-8" -Body $body -TimeoutSec 10
    if ($created.code -ne 0) { throw "创建恢复测试任务失败" }
    $taskId = [long]$created.data.id

    Wait-Until {
        [long](Invoke-SqlScalar `
            "SELECT COUNT(*) FROM ai_run_step s JOIN ai_task t ON t.current_run_id=s.run_id WHERE t.id=$taskId AND s.node_name='EXECUTE_TOOL' AND s.status='COMPLETED';") -eq 1
    } "首个工具结果 checkpoint 落库"
    Write-Host ">>> 首个工具结果已持久化，关闭一次性故障注入并恢复 Agent" -ForegroundColor Cyan
    Restore-Agent

    Wait-Until {
        (Invoke-RestMethod -Uri "$GatewayBaseUrl/api/ai/tasks/$taskId" `
            -Headers @{ Authorization = $headers.Authorization } -TimeoutSec 10).data.status -eq "WAITING_REVIEW"
    } "Agent 重启后从 checkpoint 完成任务"

    $stepCount = [long](Invoke-SqlScalar `
        "SELECT COUNT(*) FROM ai_run_step s JOIN ai_task t ON t.current_run_id=s.run_id WHERE t.id=$taskId AND s.status='COMPLETED';")
    $toolStepCount = [long](Invoke-SqlScalar `
        "SELECT COUNT(*) FROM ai_run_step s JOIN ai_task t ON t.current_run_id=s.run_id WHERE t.id=$taskId AND s.node_name='EXECUTE_TOOL' AND s.status='COMPLETED';")
    $attempt = [int](Invoke-SqlScalar `
        "SELECT r.attempt FROM ai_run r JOIN ai_task t ON t.current_run_id=r.run_id WHERE t.id=$taskId;")
    $version = [int](Invoke-SqlScalar "SELECT version FROM ai_task WHERE id=$taskId;")
    if ($stepCount -lt 12 -or $toolStepCount -ne 3 -or $attempt -lt 2 -or $version -lt 4) {
        throw "恢复证据不完整：steps=$stepCount, toolSteps=$toolStepCount, attempt=$attempt, taskVersion=$version"
    }

    [pscustomobject]@{
        TaskId = $taskId
        CheckpointSteps = $stepCount
        ToolSteps = $toolStepCount
        RunAttempts = $attempt
        TaskVersion = $version
        RecoveredToWaitingReview = $true
    } | Format-List
    Write-Host ">>> Agent checkpoint 故障恢复测试通过" -ForegroundColor Green
}
finally {
    if (-not $AgentRestored) {
        Restore-Agent
    }
}
