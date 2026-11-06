param([string]$JavaHome = $env:JAVA_HOME, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$dataRoot = Join-Path $projectRoot 'data'
$runtimeFile = Join-Path $dataRoot 'runtime.json'
function Get-RunningWorkbench {
    if (Test-Path -LiteralPath $runtimeFile) {
        try {
            $descriptor = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json
            $null = Invoke-RestMethod -Uri "http://127.0.0.1:$($descriptor.port)/api/telegram" -Headers @{'X-App-Token'=$descriptor.token} -TimeoutSec 2
            return $descriptor
        } catch { return $null }
    }
    return $null
}
$running = Get-RunningWorkbench
if (!$running) {
    if (!(Test-Path -LiteralPath (Join-Path $dataRoot 'metadata.sqlite'))) {
        $legacyRoots = @()
        if ($env:LOCALAPPDATA) {
            $legacyRoots += Join-Path $env:LOCALAPPDATA 'TelegramResourceWorkbench'
            $packagesRoot = Join-Path $env:LOCALAPPDATA 'Packages'
            if (Test-Path -LiteralPath $packagesRoot) {
                $legacyRoots += @(Get-ChildItem -LiteralPath $packagesRoot -Directory -Filter 'OpenAI.Codex_*' | ForEach-Object {
                    Join-Path $_.FullName 'LocalCache\Local\TelegramResourceWorkbench'
                })
            }
        }
        foreach ($legacyRoot in $legacyRoots) {
            if (Test-Path -LiteralPath (Join-Path $legacyRoot 'metadata.sqlite')) {
                throw "项目 data 数据库缺失，但检测到旧数据：$legacyRoot。请先恢复完整的 data 目录后再启动，避免创建空数据库。"
            }
        }
    }
    $jar = Join-Path $projectRoot 'target/telegram-resource-workbench-0.1.0-SNAPSHOT.jar'
    $stagedJar = Join-Path $projectRoot 'target/staged/telegram-resource-workbench-0.1.0-SNAPSHOT.jar'
    if (Test-Path -LiteralPath $stagedJar -PathType Leaf) {
        $updateLock = $null
        try {
            New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
            try { $updateLock = [IO.File]::Open((Join-Path $dataRoot 'instance.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
            catch { throw '项目仍在运行，请先在设置中关闭项目，再重新启动以应用更新。' }
            if (!(Test-Path -LiteralPath $jar -PathType Leaf) -or (Get-Item -LiteralPath $stagedJar).LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) {
                Move-Item -LiteralPath $stagedJar -Destination $jar -Force
                Write-Output '已应用已构建的项目更新。'
            } else {
                Remove-Item -LiteralPath $stagedJar
            }
        } finally {
            if ($updateLock) { $updateLock.Dispose() }
        }
    }
    if (!(Test-Path -LiteralPath $jar)) { throw '请先运行 scripts/build.ps1' }
    $javaExe = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { (Get-Command java.exe).Source }
    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    $arguments = @('-Xms64m','-Xmx512m', ('"-Dworkbench.data=' + $dataRoot + '"'), '-cp','"target/telegram-resource-workbench-0.1.0-SNAPSHOT.jar;target/lib/*"','io.github.telegramenhance.WorkbenchApplication')
    $process = Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $dataRoot 'backend.log') -RedirectStandardError (Join-Path $dataRoot 'backend-error.log') -PassThru
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Milliseconds 500
        $running = Get-RunningWorkbench
        if ($running) { break }
        if ($process.HasExited) { throw "后台启动失败，查看 $dataRoot\backend-error.log 和 backend.log" }
    }
    if (!$running) { throw "后台尚未就绪，查看 $dataRoot\backend.log" }
}
$localUrl = "http://127.0.0.1:$($running.port)/"
Write-Output "Telegram 资源台已启动：$localUrl"
if (!$NoBrowser) { Start-Process ($localUrl + '#token=' + $running.token) -WindowStyle Hidden }
