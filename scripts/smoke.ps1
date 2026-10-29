param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot ('.runtime/smoke-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$javaExe = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { (Get-Command java.exe).Source }
$script:checks = 0
function Assert-Check($condition, [string]$name) {
    if (!$condition) { throw "FAIL: $name" }
    $script:checks++
    Write-Output "PASS: $name"
}
function Start-TestBackend {
    $arguments = @('-Xmx256m', ('"-Dworkbench.data=' + $testRoot + '"'), '-cp', '"target/telegram-resource-workbench-0.1.0-SNAPSHOT.jar;target/lib/*"', 'io.github.telegramenhance.WorkbenchApplication')
    $script:backend = Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $testRoot 'backend.log') -RedirectStandardError (Join-Path $testRoot 'error.log') -PassThru
    $runtimeFile = Join-Path $testRoot 'runtime.json'
    for ($i=0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-Path -LiteralPath $runtimeFile) {
            try {
                $descriptor = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json
                $script:baseUrl = "http://127.0.0.1:$($descriptor.port)"
                $script:headers = @{'X-App-Token'=$descriptor.token}
                $null = Invoke-RestMethod -Uri ($script:baseUrl + '/api/telegram') -Headers $script:headers -TimeoutSec 2
                return
            } catch { }
        }
        if ($script:backend.HasExited) { throw "Backend failed. Inspect $testRoot" }
    }
    throw "Backend readiness timed out. Inspect $testRoot"
}
function Request([string]$method, [string]$path, $body=@{}) {
    if (!$path.StartsWith('/api/')) { $path = '/api' + $path }
    if ($method -eq 'GET') { return Invoke-RestMethod -Uri ($script:baseUrl + $path) -Headers $script:headers -TimeoutSec 35 }
    return Invoke-RestMethod -Method $method -Uri ($script:baseUrl + $path) -Headers $script:headers -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 6 -Compress) -TimeoutSec 35
}
function Status-Code([string]$path, $requestHeaders=@{}) {
    try { return [int](Invoke-WebRequest -Uri ($script:baseUrl + $path) -Headers $requestHeaders -TimeoutSec 5).StatusCode }
    catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } throw }
}
function Post-Status-Code([string]$path, $requestHeaders=@{}, $body=@{}) {
    try { return [int](Invoke-WebRequest -Method POST -Uri ($script:baseUrl + $path) -Headers $requestHeaders -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 5).StatusCode }
    catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } throw }
}
function Stop-TestBackend {
    $null = Request 'POST' '/api/app/exit'
    if (!$script:backend.WaitForExit(40000)) { throw 'Graceful exit timed out' }
    Assert-Check ($script:backend.ExitCode -eq 0) 'graceful shutdown exits cleanly'
}
try {
    Start-TestBackend
    $bootstrap = Request 'GET' '/api/bootstrap'
    Assert-Check $bootstrap.telegram.nativeLoaded 'Windows TDLight JNI loads'
    Assert-Check ($bootstrap.telegram.nativeVersion -ne 'unknown') 'native version queried through JNI'
    Assert-Check ($bootstrap.diagnostics.database.integrity -eq 'ok') 'SQLite quick_check'
    Assert-Check ($bootstrap.diagnostics.database.journalMode -eq 'wal' -and $bootstrap.diagnostics.database.synchronous -eq 'FULL') 'SQLite WAL + FULL durability'
    Assert-Check ($bootstrap.stage -eq 'M5') 'M5 observation build reports its current stage'
    Assert-Check ((Status-Code '/api/bootstrap') -eq 401) 'missing token rejected'
    Assert-Check ((Status-Code '/%61pi/bootstrap') -ne 200) 'encoded API path cannot bypass token'
    Assert-Check ((Status-Code '/api;x/bootstrap') -ne 200) 'matrix API path cannot bypass token'
    $badOrigin=@{} + $script:headers; $badOrigin.Origin='https://example.com'
    Assert-Check ((Status-Code '/api/bootstrap' $badOrigin) -eq 403) 'foreign Origin rejected'
    $badHost=@{} + $script:headers; $badHost.Host='localhost'
    Assert-Check ((Status-Code '/api/bootstrap' $badHost) -eq 403) 'foreign Host rejected'
    Assert-Check ((Status-Code '/') -eq 200) 'frontend served locally'
    Assert-Check ((Post-Status-Code '/api/rules/name' @{} @{link='https://t.me/synthetic/1'}) -eq 401) 'rule name lookup requires local token'
    Assert-Check ((Post-Status-Code '/api/rules/name' $script:headers @{link='https://t.me/synthetic/1'}) -eq 400) 'rule name lookup requires Telegram authorization'
    foreach($ruleRead in @('/api/rules','/api/rules/by-operation/00000000-0000-0000-0000-000000000000','/api/rules/00000000-0000-0000-0000-000000000000/versions')) {
        Assert-Check ((Status-Code $ruleRead) -eq 401) 'rule metadata requires local token'
        Assert-Check ((Status-Code $ruleRead $script:headers) -eq 400) 'rule metadata requires Telegram authorization'
    }
    foreach($ruleWrite in @('/api/rules','/api/rules/00000000-0000-0000-0000-000000000000/enabled','/api/rules/00000000-0000-0000-0000-000000000000/observe','/api/rules/00000000-0000-0000-0000-000000000000/preview')) {
        Assert-Check ((Post-Status-Code $ruleWrite @{} @{}) -eq 401) 'rule mutation requires local token'
        Assert-Check ((Post-Status-Code $ruleWrite $script:headers @{}) -eq 400) 'rule mutation requires Telegram authorization'
    }
    foreach($downloadRuleRead in @('/api/rules/download','/api/rules/download/00000000-0000-0000-0000-000000000000','/api/rules/download/by-operation/00000000-0000-0000-0000-000000000000','/api/rules/download/00000000-0000-0000-0000-000000000000/versions')) {
        Assert-Check ((Status-Code $downloadRuleRead) -eq 401) 'download rules require local token'
        Assert-Check ((Status-Code $downloadRuleRead $script:headers) -eq 400) 'download rules require Telegram authorization'
    }
    foreach($downloadRuleWrite in @('/api/rules/download','/api/rules/download/00000000-0000-0000-0000-000000000000/enabled','/api/rules/download/00000000-0000-0000-0000-000000000000/check','/api/rules/download/00000000-0000-0000-0000-000000000000/takeover')) {
        $downloadRuleBody=@{operationId='00000000-0000-0000-0000-000000000000';revision=1;taskId='00000000-0000-0000-0000-000000000000'}
        Assert-Check ((Post-Status-Code $downloadRuleWrite @{} $downloadRuleBody) -eq 401) 'download rule execution requires local token'
        Assert-Check ((Post-Status-Code $downloadRuleWrite $script:headers $downloadRuleBody) -eq 400) 'download rule execution requires Telegram authorization'
    }
    Assert-Check ((Status-Code '/api/library/catalog' $script:headers) -eq 400 -and (Status-Code '/api/library/channels' $script:headers) -eq 400) 'catalog and channels require an authorized Telegram account'
    Assert-Check ((Status-Code '/api/library/catalog?limit=not-a-number' $script:headers) -eq 400) 'malformed catalog pagination is rejected as a client error'
    Assert-Check ((Status-Code '/api/library/posts/sample/preview') -eq 401) 'preview metadata requires the local access token'
    Assert-Check ((Status-Code '/api/library/posts/sample/preview' $script:headers) -eq 400) 'preview metadata requires an authorized Telegram account'
    Assert-Check ((Status-Code '/api/library/posts/sample/media-preview?mediaKey=sample') -eq 401) 'comment preview requires the local access token'
    Assert-Check ((Status-Code '/api/library/posts/sample/media-preview?mediaKey=sample' $script:headers) -eq 400) 'comment preview requires an authorized Telegram account'
    Assert-Check ((Status-Code '/api/library/import/channels') -eq 401) 'managed channel discovery requires the local token'
    Assert-Check ((Status-Code '/api/library/import/channels' $script:headers) -eq 400) 'managed channel discovery requires Telegram authorization'
    Assert-Check ((Post-Status-Code '/api/library/import/preview' @{} @{channelId='-1001'}) -eq 401) 'channel preview requires the local token'
    Assert-Check ((Post-Status-Code '/api/library/import/preview' $script:headers @{channelId='-1001'}) -eq 400) 'channel preview requires Telegram authorization'
    Assert-Check ((Post-Status-Code '/api/library/import/posts' @{} @{channelId='-1001';messageId='1'}) -eq 401) 'channel import requires the local token'
    Assert-Check ((Post-Status-Code '/api/library/import/posts' $script:headers @{channelId='-1001';messageId='1'}) -eq 400) 'channel import requires Telegram authorization'
    Assert-Check ((Post-Status-Code '/api/library/import/history' @{} @{channelId='-1001';cursor=$null}) -eq 401) 'channel history requires the local token'
    Assert-Check ((Post-Status-Code '/api/library/import/history' $script:headers @{channelId='-1001';cursor=$null}) -eq 400) 'channel history requires Telegram authorization'
    Assert-Check ((Status-Code '/api/library/import/all/state?channelId=-1001') -eq 401) 'durable channel progress requires the local token'
    Assert-Check ((Status-Code '/api/library/import/all/state?channelId=-1001' $script:headers) -eq 400) 'durable channel progress requires Telegram authorization'
    Assert-Check ((Post-Status-Code '/api/library/import/all/start' @{} @{channelId='-1001';restart=$false}) -eq 401) 'full channel import start requires the local token'
    Assert-Check ((Post-Status-Code '/api/library/import/all/start' $script:headers @{channelId='-1001';restart=$false}) -eq 400) 'full channel import start requires Telegram authorization'
    Assert-Check ((Post-Status-Code '/api/library/import/all/step' @{} @{channelId='-1001';runId='synthetic';revision=0}) -eq 401) 'full channel import step requires the local token'
    Assert-Check ((Post-Status-Code '/api/library/import/all/step' $script:headers @{channelId='-1001';runId='synthetic';revision=0}) -eq 400) 'full channel import step requires Telegram authorization'
    Assert-Check ((Post-Status-Code '/api/transfers/download/preview' @{} @{link='https://t.me/synthetic/1'}) -eq 401) 'download source preview requires the local token'
    Assert-Check ((Post-Status-Code '/api/transfers/download/preview' $script:headers @{link='https://t.me/synthetic/1'}) -eq 400) 'download source preview requires Telegram authorization'
    $previewPosition=@{id='00000000-0000-0000-0000-000000000000';cursor='1';pageNumber=1}
    Assert-Check ((Post-Status-Code '/api/transfers/download/preview/next' @{} $previewPosition) -eq 401) 'download source paging requires the local token'
    Assert-Check ((Post-Status-Code '/api/transfers/download/preview/next' $script:headers $previewPosition) -eq 400) 'download source paging requires Telegram authorization'
    foreach($channelPreviewRead in @('/api/transfers/download/channels','/api/transfers/download/channels/preview/00000000-0000-0000-0000-000000000000')) {
        Assert-Check ((Status-Code $channelPreviewRead) -eq 401) 'download channel metadata requires local token'
        Assert-Check ((Status-Code $channelPreviewRead $script:headers) -eq 400) 'download channel metadata requires Telegram authorization'
    }
    Assert-Check ((Post-Status-Code '/api/transfers/download/channels/preview' @{} @{source='-1001';tags='';keywords=''}) -eq 401) 'download channel preview requires local token'
    Assert-Check ((Post-Status-Code '/api/transfers/download/channels/preview' $script:headers @{source='-1001';tags='';keywords=''}) -eq 400) 'download channel preview requires Telegram authorization'
    Assert-Check ((Post-Status-Code '/api/transfers/download/channels/preview/00000000-0000-0000-0000-000000000000/next' @{} @{revision=1}) -eq 401) 'download channel step requires local token'
    Assert-Check ((Post-Status-Code '/api/transfers/download/channels/preview/00000000-0000-0000-0000-000000000000/next' $script:headers @{revision=1}) -eq 400) 'download channel step requires Telegram authorization'
    foreach($batchRead in @('/api/transfers/download/batches','/api/transfers/download/batches/00000000-0000-0000-0000-000000000000','/api/transfers/download/batches/preview/00000000-0000-0000-0000-000000000000','/api/transfers/download/batches/by-operation/00000000-0000-0000-0000-000000000000')) {
        Assert-Check ((Status-Code $batchRead) -eq 401) 'batch metadata requires local token'
        Assert-Check ((Status-Code $batchRead $script:headers) -eq 400) 'batch metadata requires Telegram authorization'
    }
    foreach($batchWrite in @('/api/transfers/download/batches/00000000-0000-0000-0000-000000000000/pause','/api/transfers/download/batches/00000000-0000-0000-0000-000000000000/resume','/api/transfers/download/batches/preview','/api/transfers/download/batches/preview/00000000-0000-0000-0000-000000000000/next','/api/transfers/download/batches')) {
        $batchBody=@{scanId='00000000-0000-0000-0000-000000000000';revision=1;postKeys=@('key');types=@('VIDEO');targetDirectory='C:\test';operationId='00000000-0000-0000-0000-000000000000';previewId='00000000-0000-0000-0000-000000000000'}
        Assert-Check ((Post-Status-Code $batchWrite @{} $batchBody) -eq 401) 'batch preparation and creation require local token'
        Assert-Check ((Post-Status-Code $batchWrite $script:headers $batchBody) -eq 400) 'batch preparation and creation require Telegram authorization'
    }
    $sourceThumbnailPath='/api/transfers/download/preview/00000000-0000-0000-0000-000000000000/thumbnail?pageNumber=0&mediaKey=synthetic'
    Assert-Check ((Status-Code $sourceThumbnailPath) -eq 401) 'download source thumbnail requires the local token'
    Assert-Check ((Status-Code $sourceThumbnailPath $script:headers) -eq 400) 'download source thumbnail requires Telegram authorization'
    $downloadTaskPath='/api/transfers/download/tasks'
    Assert-Check ((Post-Status-Code ($downloadTaskPath+'/repair-source-links')) -eq 401) 'source link repair requires the local token'
    Assert-Check ((Post-Status-Code ($downloadTaskPath+'/repair-source-links') $script:headers) -eq 400) 'source link repair requires Telegram authorization'
    Assert-Check ((Status-Code $downloadTaskPath) -eq 401) 'download task list requires the local token'
    Assert-Check ((Status-Code $downloadTaskPath $script:headers) -eq 400) 'download task list requires Telegram authorization'
    $downloadCreate=@{operationId='00000000-0000-0000-0000-000000000000';sourceId='00000000-0000-0000-0000-000000000000';mediaKeys=@('synthetic');includeMain=$false;targetDirectory='D:\synthetic-downloads'}
    Assert-Check ((Post-Status-Code $downloadTaskPath @{} $downloadCreate) -eq 401) 'download task create requires the local token'
    Assert-Check ((Post-Status-Code $downloadTaskPath $script:headers $downloadCreate) -eq 400) 'download task create requires Telegram authorization'
    $downloadTaskIdPath=$downloadTaskPath+'/00000000-0000-0000-0000-000000000000'
    Assert-Check ((Post-Status-Code ($downloadTaskIdPath+'/pause')) -eq 401) 'download task pause requires the local token'
    Assert-Check ((Post-Status-Code ($downloadTaskIdPath+'/pause') $script:headers) -eq 400) 'download task pause requires Telegram authorization'
    Assert-Check ((Post-Status-Code ($downloadTaskIdPath+'/resume')) -eq 401) 'download task resume requires the local token'
    Assert-Check ((Post-Status-Code ($downloadTaskIdPath+'/resume') $script:headers) -eq 400) 'download task resume requires Telegram authorization'
    $downloadOperationPath=$downloadTaskPath+'/by-operation/00000000-0000-0000-0000-000000000000'
    Assert-Check ((Status-Code $downloadOperationPath) -eq 401) 'download operation lookup requires the local token'
    Assert-Check ((Status-Code $downloadOperationPath $script:headers) -eq 400) 'download operation lookup requires Telegram authorization'
    $uploadPreviewPath='/api/transfers/upload/preview'
    $uploadPreviewBody=@{link='https://t.me/synthetic/1';sourceDirectory='D:\synthetic-source';archiveDirectory='D:\synthetic-archive';recursive=$true;captionMode='FILENAME'}
    Assert-Check ((Post-Status-Code $uploadPreviewPath @{} $uploadPreviewBody) -eq 401) 'upload preparation requires the local token'
    Assert-Check ((Post-Status-Code $uploadPreviewPath $script:headers $uploadPreviewBody) -eq 400) 'upload preparation requires Telegram authorization'
    $uploadSnapshotPath=$uploadPreviewPath+'/00000000-0000-0000-0000-000000000000'
    Assert-Check ((Status-Code $uploadSnapshotPath) -eq 401) 'upload snapshot requires the local token'
    Assert-Check ((Status-Code $uploadSnapshotPath $script:headers) -eq 400) 'upload snapshot requires Telegram authorization'
    Assert-Check ((Post-Status-Code ($uploadSnapshotPath+'/verify')) -eq 401) 'upload recheck requires the local token'
    Assert-Check ((Post-Status-Code ($uploadSnapshotPath+'/verify') $script:headers) -eq 400) 'upload recheck requires Telegram authorization'
    $uploadTaskPath='/api/transfers/upload/tasks'
    Assert-Check ((Status-Code $uploadTaskPath) -eq 401) 'upload tasks require the local token'
    Assert-Check ((Status-Code $uploadTaskPath $script:headers) -eq 400) 'upload tasks require Telegram authorization'
    $uploadTaskBody=@{operationId='00000000-0000-0000-0000-000000000000';previewId='00000000-0000-0000-0000-000000000000'}
    Assert-Check ((Post-Status-Code $uploadTaskPath @{} $uploadTaskBody) -eq 401) 'upload creation requires the local token'
    Assert-Check ((Post-Status-Code $uploadTaskPath $script:headers $uploadTaskBody) -eq 400) 'upload creation requires Telegram authorization'
    foreach($uploadAction in @('start','pause','reconcile','cancel','recover-pending','retry-check','retry-failed','takeover')) {
        $uploadActionPath=$uploadTaskPath+'/00000000-0000-0000-0000-000000000000/'+$uploadAction
        Assert-Check ((Post-Status-Code $uploadActionPath) -eq 401) "upload $uploadAction requires the local token"
        Assert-Check ((Post-Status-Code $uploadActionPath $script:headers) -eq 400) "upload $uploadAction requires Telegram authorization"
    }
    $retryOperationPath=$uploadTaskPath+'/00000000-0000-0000-0000-000000000000/retry-operations/00000000-0000-0000-0000-000000000000'
    Assert-Check ((Status-Code $retryOperationPath) -eq 401) 'retry operation lookup requires local token'
    Assert-Check ((Status-Code $retryOperationPath $script:headers) -eq 400) 'retry operation lookup requires Telegram authorization'
    $acceptDocumentPath=$uploadTaskPath+'/00000000-0000-0000-0000-000000000000/groups/1/accept-document'
    Assert-Check ((Post-Status-Code $acceptDocumentPath) -eq 401) 'document acceptance requires the local token'
    Assert-Check ((Post-Status-Code $acceptDocumentPath $script:headers) -eq 400) 'document acceptance requires Telegram authorization'
    # Synthetic values only. No user credentials are read or used in this test.
    $inputSettings = @{apiId=12345;apiHash=('a' * 32);proxy=@{type='SOCKS5';host='127.0.0.1';port=1;username='smoke';password='synthetic-only'}}
    $saved = Request 'PUT' '/api/settings' $inputSettings
    Assert-Check ($saved.apiHashStored -and $saved.proxy.passwordStored -and !$saved.apiHash) 'settings response never exposes secrets'
    $null = Request 'POST' '/telegram/connect'
    for ($i=0; $i -lt 30; $i++) {
        $auth = Request 'GET' '/api/telegram'
        if ($auth.authState -eq 'WAIT_PHONE' -or $auth.error) { break }
        Start-Sleep -Milliseconds 200
    }
    Assert-Check ($auth.authState -eq 'WAIT_PHONE' -and !$auth.error) 'TDLib parameters and proxy initialize without deadlock (no phone submitted)'
    $resendDenied = $false
    try { $null = Request 'POST' '/telegram/auth/resend' }
    catch { $resendDenied = ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 400) }
    Assert-Check $resendDenied 'resend outside a server code challenge is rejected'
    $refreshDenied = $false
    try { $null = Request 'POST' '/library/posts/synthetic-post/refresh' }
    catch { $refreshDenied = ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 400) }
    Assert-Check $refreshDenied 'saved-post refresh requires an authorized Telegram account'
    $proxy = Request 'POST' '/proxy/test'
    Assert-Check ($proxy.level -eq 'ERROR') 'unreachable proxy is not reported as connected'
    $null = Request 'POST' '/telegram/disconnect'
    $proxy = Request 'POST' '/proxy/test'
    Assert-Check ($proxy.level -eq 'ERROR') 'proxy check after disconnect handles a fresh client'
    $arguments=@(('"-Dworkbench.data=' + $testRoot + '"'),'-cp','"target/telegram-resource-workbench-0.1.0-SNAPSHOT.jar;target/lib/*"','io.github.telegramenhance.WorkbenchApplication')
    $second = Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $testRoot 'second.log') -RedirectStandardError (Join-Path $testRoot 'second-error.log') -PassThru
    $exited=$second.WaitForExit(10000)
    Assert-Check ($exited -and $second.ExitCode -ne 0) 'second instance refused'
    Assert-Check ((Request 'GET' '/api/settings').apiId -eq 12345) 'second instance preserves first instance'
    Stop-TestBackend
    Start-TestBackend
    $persisted = Request 'GET' '/api/settings'
    Assert-Check ($persisted.apiId -eq 12345 -and $persisted.apiHashStored -and $persisted.proxy.passwordStored) 'settings survive full process restart'
    Stop-TestBackend
    Assert-Check (!(Test-Path -LiteralPath (Join-Path $testRoot 'runtime.json'))) 'handshake removed after shutdown'
    Write-Output "$script:checks smoke checks passed. Test data: $testRoot"
} finally {
    if ($script:backend -and !$script:backend.HasExited) {
        try { $null = Request 'POST' '/api/app/exit'; $null = $script:backend.WaitForExit(40000) } catch { }
    }
}
