param(
    [Parameter(Mandatory=$true)]
    [string]$RemoteUrl
)

if (Test-Path ".git") {
    Write-Host "Already a git repo" -ForegroundColor Red
    exit 1
}

Write-Host "Initializing git..." -ForegroundColor Cyan
git init -b main

function Commit-Step {
    param([string]$Msg, [string[]]$Paths)
    Write-Host "`$Msg" -ForegroundColor Yellow
    git add $Paths
    git commit -m $Msg
}

Commit-Step "init: root files" @("pom.xml", "README.md", ".gitignore")
Commit-Step "init: common module" @("xplanet-common/")
Commit-Step "init: article module" @("xplanet-article/")
Commit-Step "init: search module" @("xplanet-search/")
Commit-Step "init: gateway module" @("xplanet-gateway/")
Commit-Step "init: api module" @("xplanet-api/")
Commit-Step "init: canal client" @("xplanet-canal-client/")
Commit-Step "init: start module" @("xplanet-start/")

Write-Host "Adding remote..." -ForegroundColor Cyan
git remote add origin $RemoteUrl

Write-Host "Pushing..." -ForegroundColor Cyan
git push -u origin main

Write-Host "`Done!" -ForegroundColor Green