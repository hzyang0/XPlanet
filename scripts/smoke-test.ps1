#Requires -Version 5.1
<#
.SYNOPSIS
  验证 XPlanet 四个 Java 服务、点赞最终一致性和 AI 控制面可靠命令链路。

.DESCRIPTION
  运行前需启动 MySQL、Redis、RocketMQ 以及 8081/8082/8083/8084 四个应用服务。
  脚本会登录演示账号，检查文章读接口，并对指定文章执行一次状态变化、幂等点赞
  和反向状态变化。最终会恢复原点赞状态和文章点赞数；已处理的 Outbox/投影审计记录会保留。

.EXAMPLE
  .\scripts\smoke-test.ps1

.EXAMPLE
  .\scripts\smoke-test.ps1 -ArticleId 2 -Username alice -Password password
#>
param(
    [long]$ArticleId = 2,
    [long]$InvalidArticleId = 999999999,
    [string]$Username = "alice",
    [string]$Password = "password",
    [string]$MysqlContainer = "xp-mysql",
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 docker，无法核对点赞 Outbox 和持久化投影。"
}

function Invoke-SqlScalar([string]$Sql) {
    $value = docker exec -e MYSQL_PWD=root123 $MysqlContainer `
        mysql -N -B -uroot xplanet -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "SQL 执行失败：$Sql"
    }
    return ($value | Out-String).Trim()
}

function Wait-Until([scriptblock]$Condition, [string]$Description) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "等待超时：$Description（${TimeoutSeconds}s）"
}

function Assert-BusinessSuccess($Response, [string]$Description) {
    if ($null -eq $Response -or $Response.code -ne 0) {
        $json = $Response | ConvertTo-Json -Compress -Depth 6
        throw "$Description 失败：$json"
    }
}

function ConvertFrom-Utf8Base64([string]$Value) {
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
}

Write-Host ">>> 检查服务健康状态" -ForegroundColor Cyan
$health = 8081, 8082, 8083, 8084 | ForEach-Object {
    $response = Invoke-RestMethod -Uri "http://localhost:$_/actuator/health" -TimeoutSec 5
    if ($response.status -ne "UP") {
        throw "服务端口 $_ 健康状态不是 UP"
    }
    "${_}:UP"
}
$agentHealth = docker inspect -f "{{.State.Health.Status}}" xp-agent 2>$null
if ($agentHealth -ne "healthy") {
    throw "Agent 容器健康状态不是 healthy：$agentHealth"
}
$health += "agent:healthy"

Write-Host ">>> 登录并验证文章读接口" -ForegroundColor Cyan
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "http://localhost:8083/api/user/login" `
    -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 10
Assert-BusinessSuccess $login "登录"
if ([string]::IsNullOrWhiteSpace($login.data.token)) {
    throw "登录响应中没有 token"
}
$headers = @{ Authorization = "Bearer $($login.data.token)" }

Write-Host ">>> 验证 AI Agent、证据报告、人工审核和幂等发布闭环" -ForegroundColor Cyan
$unauthenticatedAi = Invoke-RestMethod -Uri "http://localhost:8084/api/ai/tasks" -TimeoutSec 5
if ($unauthenticatedAi.code -ne 2001) {
    throw "未登录读取 AI 任务应返回业务码 2001，实际为 $($unauthenticatedAi.code)"
}
$aiKey = "smoke-$([Guid]::NewGuid().ToString('N'))"
$aiBody = @{
    question = "Explain the XPlanet Outbox reliability design"
    maxSources = 3
    maxToolCalls = 5
    maxTokens = 4000
    deadlineSeconds = 120
} | ConvertTo-Json
$aiHeaders = @{ Authorization = $headers.Authorization; "Idempotency-Key" = $aiKey }
$aiCreated = Invoke-RestMethod -Method Post -Uri "http://localhost:8084/api/ai/tasks" `
    -Headers $aiHeaders -ContentType "application/json; charset=utf-8" -Body $aiBody -TimeoutSec 10
Assert-BusinessSuccess $aiCreated "创建 AI 任务"
$aiDuplicate = Invoke-RestMethod -Method Post -Uri "http://localhost:8084/api/ai/tasks" `
    -Headers $aiHeaders -ContentType "application/json; charset=utf-8" -Body $aiBody -TimeoutSec 10
Assert-BusinessSuccess $aiDuplicate "重复创建 AI 任务"
if ($aiDuplicate.data.id -ne $aiCreated.data.id) {
    throw "相同幂等键重复请求产生了不同任务"
}
$conflictBody = @{ question = "A different request" } | ConvertTo-Json
$aiConflict = Invoke-RestMethod -Method Post -Uri "http://localhost:8084/api/ai/tasks" `
    -Headers $aiHeaders -ContentType "application/json; charset=utf-8" -Body $conflictBody -TimeoutSec 10
if ($aiConflict.code -ne 4003) {
    throw "相同幂等键用于不同请求应返回4003，实际为 $($aiConflict.code)"
}
$aiTask = Invoke-RestMethod -Uri "http://localhost:8084/api/ai/tasks/$($aiCreated.data.id)" `
    -Headers $headers -TimeoutSec 10
Assert-BusinessSuccess $aiTask "读取 AI 任务"
$otherLoginBody = @{ username = "bob"; password = "password" } | ConvertTo-Json
$otherLogin = Invoke-RestMethod -Method Post -Uri "http://localhost:8083/api/user/login" `
    -ContentType "application/json; charset=utf-8" -Body $otherLoginBody -TimeoutSec 10
Assert-BusinessSuccess $otherLogin "第二账号登录"
$otherHeaders = @{ Authorization = "Bearer $($otherLogin.data.token)" }
$otherRead = Invoke-RestMethod -Uri "http://localhost:8084/api/ai/tasks/$($aiCreated.data.id)" `
    -Headers $otherHeaders -TimeoutSec 10
if ($otherRead.code -ne 4001) {
    throw "其他用户读取 AI 任务应返回业务码 4001，实际为 $($otherRead.code)"
}
Wait-Until {
    [long](Invoke-SqlScalar "SELECT COUNT(*) FROM ai_outbox WHERE aggregate_id=$($aiCreated.data.id) AND event_type='AI_TASK_REQUESTED' AND status=2;") -eq 1
} "AI任务请求 Outbox 发送"
Wait-Until {
    (Invoke-RestMethod -Uri "http://localhost:8084/api/ai/tasks/$($aiCreated.data.id)" `
        -Headers $headers -TimeoutSec 10).data.status -eq "WAITING_REVIEW"
} "Agent 完成研究并进入人工审核"
$aiReport = Invoke-RestMethod -Uri "http://localhost:8084/api/ai/tasks/$($aiCreated.data.id)/report" `
    -Headers $headers -TimeoutSec 10
Assert-BusinessSuccess $aiReport "读取 AI 报告"
if ($aiReport.data.sources.Count -lt 1 -or $aiReport.data.evidence.Count -lt 1 `
        -or $aiReport.data.citations.Count -lt 1) {
    throw "AI 报告缺少来源、证据或引用"
}
$evidenceIds = @($aiReport.data.evidence | ForEach-Object { [long]$_.id })
foreach ($citation in $aiReport.data.citations) {
    if ([long]$citation.evidenceId -notin $evidenceIds) {
        throw "报告引用指向未知 evidenceId=$($citation.evidenceId)"
    }
}
$progressEventCount = [long](docker exec xp-redis redis-cli XLEN "xp:ai:task:$($aiCreated.data.id):events")
if ($progressEventCount -lt 7) {
    throw "Agent 进度事件不完整，实际为 $progressEventCount"
}
$otherReport = Invoke-RestMethod -Uri "http://localhost:8084/api/ai/tasks/$($aiCreated.data.id)/report" `
    -Headers $otherHeaders -TimeoutSec 10
if ($otherReport.code -ne 4004) {
    throw "其他用户读取 AI 报告应返回业务码 4004，实际为 $($otherReport.code)"
}
$approved = Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8084/api/ai/tasks/$($aiCreated.data.id)/report/approve" `
    -Headers $headers -ContentType "application/json; charset=utf-8" -Body "{}" -TimeoutSec 10
Assert-BusinessSuccess $approved "批准并发布 AI 报告"
$approvedAgain = Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8084/api/ai/tasks/$($aiCreated.data.id)/report/approve" `
    -Headers $headers -ContentType "application/json; charset=utf-8" -Body "{}" -TimeoutSec 10
Assert-BusinessSuccess $approvedAgain "重复批准 AI 报告"
if ($approved.data.status -ne "PUBLISHED" -or $approved.data.publishArticleId -ne $approvedAgain.data.publishArticleId) {
    throw "AI 报告未发布或重复批准产生了不同文章"
}
$publishedArticle = Invoke-RestMethod `
    -Uri "http://localhost:8081/api/article/$($approved.data.publishArticleId)" -TimeoutSec 10
Assert-BusinessSuccess $publishedArticle "读取 AI 发布文章"
if ([long](Invoke-SqlScalar "SELECT COUNT(*) FROM ai_published_article WHERE report_id=$($approved.data.id);") -ne 1) {
    throw "AI 报告没有保持唯一文章发布投影"
}
if ([long](Invoke-SqlScalar "SELECT COUNT(*) FROM ai_task WHERE user_id=$($login.data.userId) AND idempotency_key='$aiKey';") -ne 1) {
    throw "AI 任务幂等键没有约束为单行"
}

Write-Host ">>> 验证 AI 取消命令和重复取消幂等" -ForegroundColor Cyan
$cancelKey = "smoke-cancel-$([Guid]::NewGuid().ToString('N'))"
$cancelHeaders = @{ Authorization = $headers.Authorization; "Idempotency-Key" = $cancelKey }
$cancelCreated = Invoke-RestMethod -Method Post -Uri "http://localhost:8084/api/ai/tasks" `
    -Headers $cancelHeaders -ContentType "application/json; charset=utf-8" -Body $aiBody -TimeoutSec 10
Assert-BusinessSuccess $cancelCreated "创建待取消 AI 任务"
$aiCancelled = Invoke-RestMethod -Method Delete `
    -Uri "http://localhost:8084/api/ai/tasks/$($cancelCreated.data.id)" -Headers $headers -TimeoutSec 10
Assert-BusinessSuccess $aiCancelled "取消 AI 任务"
$aiCancelAgain = Invoke-RestMethod -Method Delete `
    -Uri "http://localhost:8084/api/ai/tasks/$($cancelCreated.data.id)" -Headers $headers -TimeoutSec 10
Assert-BusinessSuccess $aiCancelAgain "重复取消 AI 任务"
if ($aiCancelled.data.status -ne "CANCELLED" -or $aiCancelAgain.data.status -ne "CANCELLED") {
    throw "AI 取消没有保持幂等终态"
}
Wait-Until {
    [long](Invoke-SqlScalar "SELECT COUNT(*) FROM ai_outbox WHERE aggregate_id=$($cancelCreated.data.id) AND event_type='AI_TASK_CANCELLED' AND status=2;") -eq 1
} "AI任务取消 Outbox 发送"

$article = Invoke-RestMethod -Uri "http://localhost:8081/api/article/$ArticleId" -TimeoutSec 10
Assert-BusinessSuccess $article "文章详情"
$articleList = Invoke-RestMethod -Uri "http://localhost:8081/api/article/list?pageNum=1&pageSize=10" -TimeoutSec 10
Assert-BusinessSuccess $articleList "文章列表"

Write-Host ">>> 验证不存在文章不会写入点赞关系或 Outbox" -ForegroundColor Cyan
$invalidRelationBefore = [long](Invoke-SqlScalar `
    "SELECT COUNT(*) FROM like_relation WHERE user_id=$($login.data.userId) AND article_id=$InvalidArticleId;")
$invalidOutboxBefore = [long](Invoke-SqlScalar `
    "SELECT COUNT(*) FROM like_outbox WHERE user_id=$($login.data.userId) AND article_id=$InvalidArticleId;")
$invalidLike = Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/like/$InvalidArticleId" `
    -Headers $headers -TimeoutSec 10
if ($invalidLike.code -ne 3001) {
    throw "不存在文章点赞应返回业务码 3001，实际为 $($invalidLike.code)"
}
$invalidRelationAfter = [long](Invoke-SqlScalar `
    "SELECT COUNT(*) FROM like_relation WHERE user_id=$($login.data.userId) AND article_id=$InvalidArticleId;")
$invalidOutboxAfter = [long](Invoke-SqlScalar `
    "SELECT COUNT(*) FROM like_outbox WHERE user_id=$($login.data.userId) AND article_id=$InvalidArticleId;")
if ($invalidRelationAfter -ne $invalidRelationBefore -or $invalidOutboxAfter -ne $invalidOutboxBefore) {
    throw "不存在文章点赞错误地产生了关系或 Outbox 记录"
}

Write-Host ">>> 验证文章更新的可靠缓存失效 Outbox" -ForegroundColor Cyan
$cacheOutboxBaselineId = [long](Invoke-SqlScalar `
    "SELECT COALESCE(MAX(id),0) FROM article_change_outbox;")
$storedTitle = ConvertFrom-Utf8Base64 (Invoke-SqlScalar `
    "SELECT REPLACE(TO_BASE64(title),CHAR(10),'') FROM article WHERE id=$ArticleId;")
$storedContent = ConvertFrom-Utf8Base64 (Invoke-SqlScalar `
    "SELECT REPLACE(TO_BASE64(content),CHAR(10),'') FROM article WHERE id=$ArticleId;")
$storedTags = ConvertFrom-Utf8Base64 (Invoke-SqlScalar `
    "SELECT REPLACE(TO_BASE64(tags),CHAR(10),'') FROM article WHERE id=$ArticleId;")
$articleUpdateBody = @{
    title = $storedTitle
    content = $storedContent
    tags = $storedTags
} | ConvertTo-Json
$articleUpdate = Invoke-RestMethod -Method Put -Uri "http://localhost:8081/api/article/$ArticleId" `
    -Headers $headers -ContentType "application/json; charset=utf-8" `
    -Body $articleUpdateBody -TimeoutSec 10
Assert-BusinessSuccess $articleUpdate "文章更新"
Wait-Until {
    [long](Invoke-SqlScalar `
        "SELECT COUNT(*) FROM article_change_outbox WHERE id>$cacheOutboxBaselineId AND article_id=$ArticleId AND status=2;") -eq 2
} "文章缓存立即/延迟失效事件发送"
$cacheOutboxSent = [long](Invoke-SqlScalar `
    "SELECT COUNT(*) FROM article_change_outbox WHERE id>$cacheOutboxBaselineId AND article_id=$ArticleId AND operation='UPDATE' AND status=2;")
if ($cacheOutboxSent -ne 2) {
    throw "文章更新应产生两条已发送缓存失效事件，实际为 $cacheOutboxSent"
}

$originalStatusText = Invoke-SqlScalar `
    "SELECT COALESCE(MAX(status),0) FROM like_relation WHERE user_id=$($login.data.userId) AND article_id=$ArticleId;"
$originalStatus = [int]$originalStatusText
$baselineCount = [long](Invoke-SqlScalar "SELECT like_count FROM article WHERE id=$ArticleId;")
$baselineOutboxId = [long](Invoke-SqlScalar "SELECT COALESCE(MAX(id),0) FROM like_outbox;")
$baselineDeltaId = [long](Invoke-SqlScalar "SELECT COALESCE(MAX(id),0) FROM like_count_delta;")

Write-Host ">>> 验证点赞状态机、重复请求幂等和异步计数投影" -ForegroundColor Cyan
if ($originalStatus -eq 1) {
    $duplicate = Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/like/$ArticleId" `
        -Headers $headers -TimeoutSec 10
    Assert-BusinessSuccess $duplicate "重复点赞"
    if ($duplicate.data -ne $false) {
        throw "已点赞状态下重复点赞应返回 false"
    }
    if ([long](Invoke-SqlScalar "SELECT COUNT(*) FROM like_outbox WHERE id>$baselineOutboxId;") -ne 0) {
        throw "重复点赞错误地产生了 Outbox 事件"
    }

    $cancel = Invoke-RestMethod -Method Delete -Uri "http://localhost:8082/api/like/$ArticleId" `
        -Headers $headers -TimeoutSec 10
    Assert-BusinessSuccess $cancel "取消点赞"
    Wait-Until { [long](Invoke-SqlScalar "SELECT like_count FROM article WHERE id=$ArticleId;") -eq ($baselineCount - 1) } `
        "取消点赞投影"

    $like = Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/like/$ArticleId" `
        -Headers $headers -TimeoutSec 10
    Assert-BusinessSuccess $like "恢复点赞"
    if ($like.data -ne $true) {
        throw "恢复点赞应返回 true"
    }
    Wait-Until { [long](Invoke-SqlScalar "SELECT like_count FROM article WHERE id=$ArticleId;") -eq $baselineCount } `
        "恢复点赞投影"
} else {
    $like = Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/like/$ArticleId" `
        -Headers $headers -TimeoutSec 10
    Assert-BusinessSuccess $like "首次点赞"
    if ($like.data -ne $true) {
        throw "未点赞状态下首次点赞应返回 true"
    }
    Wait-Until { [long](Invoke-SqlScalar "SELECT like_count FROM article WHERE id=$ArticleId;") -eq ($baselineCount + 1) } `
        "点赞投影"

    $duplicate = Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/like/$ArticleId" `
        -Headers $headers -TimeoutSec 10
    Assert-BusinessSuccess $duplicate "重复点赞"
    if ($duplicate.data -ne $false) {
        throw "重复点赞应返回 false"
    }
    if ([long](Invoke-SqlScalar "SELECT COUNT(*) FROM like_outbox WHERE id>$baselineOutboxId;") -ne 1) {
        throw "重复点赞错误地产生了额外 Outbox 事件"
    }

    $cancel = Invoke-RestMethod -Method Delete -Uri "http://localhost:8082/api/like/$ArticleId" `
        -Headers $headers -TimeoutSec 10
    Assert-BusinessSuccess $cancel "取消点赞"
    Wait-Until { [long](Invoke-SqlScalar "SELECT like_count FROM article WHERE id=$ArticleId;") -eq $baselineCount } `
        "取消点赞投影"
}

$finalStatus = [int](Invoke-SqlScalar `
    "SELECT COALESCE(MAX(status),0) FROM like_relation WHERE user_id=$($login.data.userId) AND article_id=$ArticleId;")
$finalCount = [long](Invoke-SqlScalar "SELECT like_count FROM article WHERE id=$ArticleId;")
$sentEvents = [long](Invoke-SqlScalar `
    "SELECT COUNT(*) FROM like_outbox WHERE id>$baselineOutboxId AND status=2;")
$appliedEvents = [long](Invoke-SqlScalar `
    "SELECT COUNT(*) FROM like_count_delta WHERE id>$baselineDeltaId AND status=1;")

if ($finalStatus -ne $originalStatus -or $finalCount -ne $baselineCount) {
    throw "测试后业务状态未恢复：relation=$finalStatus/$originalStatus, count=$finalCount/$baselineCount"
}
if ($sentEvents -ne 2 -or $appliedEvents -ne 2) {
    throw "事件链不完整：已发送 Outbox=$sentEvents，已应用投影=$appliedEvents（期望均为2）"
}

$unauthenticated = Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8082/api/like/$ArticleId" -TimeoutSec 5
if ($unauthenticated.code -ne 2001) {
    throw "未登录点赞应返回业务码 2001，实际为 $($unauthenticated.code)"
}

[pscustomobject]@{
    Health = ($health -join ", ")
    LoginUserId = $login.data.userId
    ArticleId = $article.data.id
    OriginalLikeStatusRestored = ($finalStatus -eq $originalStatus)
    LikeCountRestored = ($finalCount -eq $baselineCount)
    DuplicateLikeIgnored = $true
    MissingArticleRejected = ($invalidLike.code -eq 3001)
    CacheInvalidationEventsSent = $cacheOutboxSent
    NewOutboxEventsSent = $sentEvents
    NewProjectionEventsApplied = $appliedEvents
    UnauthenticatedWriteCode = $unauthenticated.code
    AiTaskId = $aiCreated.data.id
    AiIdempotencyVerified = ($aiDuplicate.data.id -eq $aiCreated.data.id)
    AiPrivateReadCode = $unauthenticatedAi.code
    AiCrossUserReadCode = $otherRead.code
    AiCrossUserReportCode = $otherReport.code
    AiProgressEvents = $progressEventCount
    AiEvidenceCount = $aiReport.data.evidence.Count
    AiPublishedArticleId = $approved.data.publishArticleId
    AiPublishIdempotent = ($approved.data.publishArticleId -eq $approvedAgain.data.publishArticleId)
    AiTaskCancelled = ($aiCancelled.data.status -eq "CANCELLED")
} | Format-List

Write-Host ">>> XPlanet 冒烟测试通过" -ForegroundColor Green
