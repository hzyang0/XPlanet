#Requires -Version 5.1
<#
.SYNOPSIS
  验证 XPlanet 本地三服务和点赞最终一致性链路。

.DESCRIPTION
  运行前需启动 MySQL、Redis、RocketMQ 以及 8081/8082/8083 三个应用服务。
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

Write-Host ">>> 检查服务健康状态" -ForegroundColor Cyan
$health = 8081, 8082, 8083 | ForEach-Object {
    $response = Invoke-RestMethod -Uri "http://localhost:$_/actuator/health" -TimeoutSec 5
    if ($response.status -ne "UP") {
        throw "服务端口 $_ 健康状态不是 UP"
    }
    "${_}:UP"
}

Write-Host ">>> 登录并验证文章读接口" -ForegroundColor Cyan
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "http://localhost:8083/api/user/login" `
    -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 10
Assert-BusinessSuccess $login "登录"
if ([string]::IsNullOrWhiteSpace($login.data.token)) {
    throw "登录响应中没有 token"
}
$headers = @{ Authorization = "Bearer $($login.data.token)" }

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
    NewOutboxEventsSent = $sentEvents
    NewProjectionEventsApplied = $appliedEvents
    UnauthenticatedWriteCode = $unauthenticated.code
} | Format-List

Write-Host ">>> XPlanet 冒烟测试通过" -ForegroundColor Green
