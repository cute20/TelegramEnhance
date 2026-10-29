$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeFile = Join-Path $projectRoot 'data/runtime.json'
if (!(Test-Path -LiteralPath $runtimeFile)) { Write-Output '资源台未运行'; exit 0 }
$descriptor = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json
$null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($descriptor.port)/api/app/exit" -Headers @{'X-App-Token'=$descriptor.token} -ContentType 'application/json' -Body '{}'
Write-Output '已请求安全退出；Telegram 会话保留。'
