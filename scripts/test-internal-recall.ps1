#Requires -Version 5.1
param([double]$MinimumRecall = 0.8)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

if ((docker inspect -f "{{.State.Health.Status}}" xp-agent 2>$null) -ne "healthy") {
    throw "xp-agent is not healthy"
}

$dataset = "/tmp/xplanet-internal-recall.jsonl"
docker cp "xplanet-agent/eval/internal_recall.jsonl" "xp-agent:$dataset" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "failed to copy recall dataset" }

$raw = docker exec xp-agent python -m xplanet_agent.internal_recall --dataset $dataset
if ($LASTEXITCODE -ne 0) { throw "internal recall evaluation failed: $raw" }
$summary = ($raw | Out-String) | ConvertFrom-Json
if ([double]$summary.recallAt5 -lt $MinimumRecall) {
    throw "Internal Recall@5 $($summary.recallAt5) is below $MinimumRecall"
}

$summary | ConvertTo-Json -Depth 6
