param(
    [int]$Count = 200,
    [string]$UserServiceUrl = "http://localhost:8083",
    [string]$OutputFile = "benchmark/tokens.txt"
)

if ($Count -lt 1) { throw "Count must be positive." }

$password = "seckill-load-password"
$tokens = [System.Collections.Generic.List[string]]::new()

for ($index = 1; $index -le $Count; $index++) {
    $username = "loaduser" + $index.ToString("D4")
    $payload = @{ username = $username; nickname = $username; password = $password } | ConvertTo-Json
    try {
        $session = Invoke-RestMethod -Method Post -Uri "$UserServiceUrl/api/user/register" -ContentType "application/json" -Body $payload
    } catch {
        $loginPayload = @{ username = $username; password = $password } | ConvertTo-Json
        $session = Invoke-RestMethod -Method Post -Uri "$UserServiceUrl/api/user/login" -ContentType "application/json" -Body $loginPayload
    }
    if ([string]::IsNullOrWhiteSpace($session.data.token)) { throw "Failed to obtain token for $username." }
    $tokens.Add($session.data.token)
}

$directory = Split-Path -Parent $OutputFile
if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) $OutputFile), $tokens)
Write-Host "Generated $($tokens.Count) tokens at $OutputFile"
