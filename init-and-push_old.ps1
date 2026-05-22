# Windows PowerShell 版一键初始化和上传。
# 用法:
#   1. GitHub 建空仓库,复制 URL
#   2. 在项目根目录右键 → "在终端中打开" → 运行:
#        .\init-and-push.ps1 git@github.com:your-name/xplanet.git
#
# 如果遇到执行策略报错:
#   Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser

param(
    [Parameter(Mandatory=$true)]
    [string]$RepoUrl
)

if (Test-Path ".git") {
    Write-Host "已存在 .git 目录,如需重新初始化请先删除。" -ForegroundColor Red
    exit 1
}

git init -b main | Out-Null

function Commit-Step {
    param([string]$Msg, [string[]]$Paths)
    git add @Paths
    git commit -m $Msg | Out-Null
    Write-Host "  ✓ $Msg" -ForegroundColor Green
    Start-Sleep -Seconds 2
}

Write-Host "==> 1/8 scaffold"
Commit-Step "chore: init multi-module maven scaffold" @("pom.xml", ".gitignore", "LICENSE")

Write-Host "==> 2/8 common"
Commit-Step "feat(common): unified response, exception and cache key constants" @("xplanet-common", "xplanet-api")

Write-Host "==> 3/8 article core"
Commit-Step "feat(article): caffeine+redis L2 cache with cache-aside delayed double-delete" @(
    "xplanet-article/pom.xml",
    "xplanet-article/src/main/java/com/xplanet/article/entity",
    "xplanet-article/src/main/java/com/xplanet/article/mapper",
    "xplanet-article/src/main/resources/mapper",
    "xplanet-article/src/main/java/com/xplanet/article/cache",
    "xplanet-article/src/main/java/com/xplanet/article/service",
    "xplanet-article/src/main/java/com/xplanet/article/controller",
    "xplanet-article/src/main/java/com/xplanet/article/config/RedissonConfig.java",
    "xplanet-article/src/main/java/com/xplanet/article/config/AsyncConfig.java",
    "xplanet-article/src/main/java/com/xplanet/article/ArticleApplication.java",
    "xplanet-article/src/main/resources/application.yml"
)

Write-Host "==> 4/8 article sentinel + metrics"
Commit-Step "feat(article): sentinel hot-key param flow + cache hit-rate metrics" @(
    "xplanet-article/src/main/java/com/xplanet/article/config/SentinelConfig.java"
)

Write-Host "==> 5/8 mq + interaction + user"
Commit-Step "feat(mq): broadcast L1 invalidator and batched like consumer; feat(interaction): like service" @(
    "xplanet-article/src/main/java/com/xplanet/article/mq",
    "xplanet-interaction",
    "xplanet-user"
)

Write-Host "==> 6/8 gateway"
Commit-Step "feat(gateway): redis token-bucket rate limit by IP + traceId injection" @("xplanet-gateway")

Write-Host "==> 7/8 canal client"
Commit-Step "feat(canal): binlog client as last-resort cache invalidation" @("xplanet-canal-client")

Write-Host "==> 8/8 docker + docs"
Commit-Step "chore(infra): docker-compose, prometheus, sql; docs; demo frontend" @(
    "docker", "sql", "xplanet-web", "docs", "benchmark", "README.md"
)

git remote add origin $RepoUrl
Write-Host ""
Write-Host "==> Pushing to $RepoUrl ..." -ForegroundColor Cyan
git push -u origin main
Write-Host ""
Write-Host "✅ Done." -ForegroundColor Green
